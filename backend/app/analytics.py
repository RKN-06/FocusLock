"""Course analytics for teachers: attendance, focus participation, who quit focus, who stopped attending."""
from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from .auth import teacher, utc
from .db import get_db
from .models import Attendance, ClassSession, Enrollment, FocusEvent, PendingAttendance, User

router = APIRouter()
LOW_RATE = 75.0
RECENT = 3


def pct(part: int, whole: int) -> float | None:
    return round(100.0 * part / whole, 1) if whole else None


@router.get("/courses/{course_id}/analytics")
def course_analytics(course_id: int, user: User = Depends(teacher), db: Session = Depends(get_db)):
    from .main import visible_course
    course = visible_course(db, user, course_id)
    students = db.execute(select(User.id, User.name, User.email).join(Enrollment, Enrollment.student_id == User.id)
                          .where(Enrollment.course_id == course_id).order_by(User.name)).all()
    total_students = len(students)
    sessions = db.scalars(select(ClassSession).where(ClassSession.course_id == course_id).order_by(ClassSession.starts_at, ClassSession.id)).all()
    held = [row for row in sessions if row.state != "ready"]
    held_ids = [row.id for row in held]

    by_session: dict[int, dict[str, int]] = {}
    by_student: dict[int, dict[str, int]] = {}
    qr_present: dict[int, int] = {}
    code_present: dict[int, int] = {}
    last_status: dict[int, str] = {}
    recent_present: dict[int, int] = {}
    earlier_present: dict[int, int] = {}
    if held_ids:
        for session_id, student_id, status, method in db.execute(select(Attendance.session_id, Attendance.student_id, Attendance.status, Attendance.method)
                                                                 .where(Attendance.session_id.in_(held_ids))):
            by_session.setdefault(session_id, {}).setdefault(status, 0)
            by_session[session_id][status] += 1
            by_student.setdefault(student_id, {}).setdefault(status, 0)
            by_student[student_id][status] += 1
            if status == "present" and method == "qr":
                qr_present[session_id] = qr_present.get(session_id, 0) + 1
            if status == "present" and method == "code":
                code_present[session_id] = code_present.get(session_id, 0) + 1
            if session_id == held_ids[-1]:
                last_status[student_id] = status
            if status == "present":
                bucket = recent_present if session_id in held_ids[-RECENT:] else earlier_present
                bucket[student_id] = bucket.get(student_id, 0) + 1
    pending: dict[int, int] = {}
    focus_students: dict[int, dict[str, int]] = {}
    focus_events: dict[int, dict[str, int]] = {}
    student_focus: dict[int, dict[str, int]] = {}
    if held_ids:
        pending = dict(db.execute(select(PendingAttendance.session_id, func.count()).where(PendingAttendance.session_id.in_(held_ids),
                                                                                           PendingAttendance.resolved_at.is_(None))
                                  .group_by(PendingAttendance.session_id)).all())
        for session_id, action, people, events in db.execute(select(FocusEvent.session_id, FocusEvent.action, func.count(func.distinct(FocusEvent.student_id)), func.count())
                                                             .where(FocusEvent.session_id.in_(held_ids)).group_by(FocusEvent.session_id, FocusEvent.action)):
            focus_students.setdefault(session_id, {})[action] = people
            focus_events.setdefault(session_id, {})[action] = events
        for student_id, action, events in db.execute(select(FocusEvent.student_id, FocusEvent.action, func.count())
                                                     .where(FocusEvent.session_id.in_(held_ids)).group_by(FocusEvent.student_id, FocusEvent.action)):
            student_focus.setdefault(student_id, {})[action] = events

    session_rows = []
    for row in sessions:
        counts = by_session.get(row.id, {})
        present, absent, excused = counts.get("present", 0), counts.get("absent", 0), counts.get("excused", 0)
        joined = focus_students.get(row.id, {}).get("started", 0)
        session_rows.append({
            "id": row.id, "title": row.title, "starts_at": utc(row.starts_at), "room_code": row.room_code, "state": row.state, "held": row.state != "ready",
            "present": present, "absent": absent, "excused": excused, "not_recorded": max(0, total_students - present - absent - excused) if row.state != "ready" else None,
            "rate": pct(present, total_students) if row.state != "ready" else None, "via_qr": qr_present.get(row.id, 0), "via_code": code_present.get(row.id, 0),
            "pending_review": pending.get(row.id, 0), "joined_focus": joined, "quit_focus": focus_students.get(row.id, {}).get("exited", 0),
            "interrupted_students": focus_students.get(row.id, {}).get("backgrounded", 0), "interruptions": focus_events.get(row.id, {}).get("backgrounded", 0),
        })

    student_rows, bands = [], {"90+": 0, "75-89": 0, "50-74": 0, "under 50": 0}
    for student_id, name, email in students:
        counts = by_student.get(student_id, {})
        present, absent, excused = counts.get("present", 0), counts.get("absent", 0), counts.get("excused", 0)
        rate = pct(present, len(held))
        quit_count = student_focus.get(student_id, {}).get("exited", 0)
        stopped = len(held) > RECENT and earlier_present.get(student_id, 0) > 0 and recent_present.get(student_id, 0) == 0
        never = len(held) >= RECENT and present == 0
        flag = "never" if never else "stopped" if stopped else "low" if rate is not None and len(held) >= RECENT and rate < LOW_RATE else "ok"
        if rate is not None:
            bands["90+" if rate >= 90 else "75-89" if rate >= 75 else "50-74" if rate >= 50 else "under 50"] += 1
        student_rows.append({"id": student_id, "name": name, "email": email, "present": present, "absent": absent, "excused": excused,
                             "not_recorded": max(0, len(held) - present - absent - excused), "rate": rate, "quit_focus": quit_count,
                             "interruptions": student_focus.get(student_id, {}).get("backgrounded", 0), "last_status": last_status.get(student_id),
                             "flag": flag})
    present_total = sum(row["present"] for row in student_rows)
    focus_exited_students = len([row for row in student_rows if row["quit_focus"] > 0])
    summary = {
        "students": total_students, "sessions": len(sessions), "held": len(held), "present_total": present_total,
        "attendance_rate": pct(present_total, total_students * len(held)), "average_present": round(present_total / len(held), 1) if held else None,
        "joined_focus": sum(row["joined_focus"] for row in session_rows), "quit_focus_events": sum(row["quit_focus"] for row in session_rows),
        "via_code": sum(row["via_code"] for row in session_rows), "students_who_quit_focus": focus_exited_students, "interruptions": sum(row["interruptions"] for row in session_rows),
        "stopped_attending": len([row for row in student_rows if row["flag"] == "stopped"]),
        "never_attended": len([row for row in student_rows if row["flag"] == "never"]),
        "low_attendance": len([row for row in student_rows if row["flag"] == "low"]),
    }
    return {"course": {"id": course.id, "code": course.code, "title": course.title, "term": course.term}, "summary": summary,
            "bands": bands, "sessions": session_rows, "students": student_rows, "low_rate_threshold": LOW_RATE, "recent_window": RECENT}
