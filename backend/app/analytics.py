"""Course analytics for teachers: attendance, focus participation, who quit focus, who stopped attending."""
from datetime import timedelta
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from .auth import teacher, utc
from .db import get_db
from .models import Attendance, ClassSession, Enrollment, FocusEvent, FocusExitRequest, PendingAttendance, User

router = APIRouter()
LOW_RATE = 75.0
RECENT = 3


def pct(part: int, whole: int) -> float | None:
    return round(100.0 * part / whole, 1) if whole else None


def periods(rows: list[dict], key: str) -> list[dict]:
    """Group per-session figures into weeks (starting Monday) or months, in campus time. Each row needs starts_at, present and expected."""
    from .slots import campus_zone
    zone, out = campus_zone(), {}
    for row in rows:
        local = row["starts_at"].astimezone(zone)
        if key == "week":
            start = (local - timedelta(days=local.weekday())).date()
            label, bucket = f"Week of {start.day} {start.strftime('%b')}", start.isoformat()
        else:
            label, bucket = local.strftime("%b %Y"), local.strftime("%Y-%m")
        item = out.setdefault(bucket, {"period": bucket, "label": label, "sessions": 0, "present": 0, "expected": 0, "quit_focus": 0, "breaks": 0})
        item["sessions"] += 1
        for field in ("present", "expected", "quit_focus", "breaks"):
            item[field] += row.get(field, 0)
    return [{**item, "rate": pct(item["present"], item["expected"])} for _, item in sorted(out.items())]


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

    breaks_by_session: dict[int, int] = {}
    breaks_by_student: dict[int, int] = {}
    if held_ids:
        for session_id, student_id, count in db.execute(select(FocusExitRequest.session_id, FocusExitRequest.student_id, func.count())
                                                        .where(FocusExitRequest.session_id.in_(held_ids), FocusExitRequest.kind == "exit")
                                                        .group_by(FocusExitRequest.session_id, FocusExitRequest.student_id)):
            breaks_by_session[session_id] = breaks_by_session.get(session_id, 0) + count
            breaks_by_student[student_id] = breaks_by_student.get(student_id, 0) + count
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
            "breaks": breaks_by_session.get(row.id, 0), "interrupted_students": focus_students.get(row.id, {}).get("backgrounded", 0), "interruptions": focus_events.get(row.id, {}).get("backgrounded", 0),
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
                             "not_recorded": max(0, len(held) - present - absent - excused), "rate": rate, "quit_focus": quit_count, "breaks": breaks_by_student.get(student_id, 0),
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
    timeline = [{"starts_at": row.starts_at, "present": r["present"], "expected": total_students, "quit_focus": r["quit_focus"], "breaks": r["breaks"]}
                for row, r in zip(sessions, session_rows) if r["held"]]
    summary["breaks"] = sum(row["breaks"] for row in session_rows)
    return {"course": {"id": course.id, "code": course.code, "title": course.title, "term": course.term}, "summary": summary,
            "weekly": periods(timeline, "week"), "monthly": periods(timeline, "month"),
            "bands": bands, "sessions": session_rows, "students": student_rows, "low_rate_threshold": LOW_RATE, "recent_window": RECENT}


@router.get("/courses/{course_id}/students/{student_id}/analytics")
def student_analytics(course_id: int, student_id: int, user: User = Depends(teacher), db: Session = Depends(get_db)):
    """One student's record in a course: attendance, breaks asked for, times they quit focus, with weekly, monthly and overall views."""
    from .main import visible_course
    course = visible_course(db, user, course_id)
    person = db.scalar(select(User).join(Enrollment, Enrollment.student_id == User.id).where(Enrollment.course_id == course_id, User.id == student_id))
    if person is None:
        raise HTTPException(404, "That student is not in this course")
    kind = db.scalar(select(Enrollment.kind).where(Enrollment.course_id == course_id, Enrollment.student_id == student_id))
    held = db.scalars(select(ClassSession).where(ClassSession.course_id == course_id, ClassSession.state != "ready").order_by(ClassSession.starts_at, ClassSession.id)).all()
    ids = [row.id for row in held]
    status, method = {}, {}
    exits, resumes, approved, quits, interruptions = {}, {}, {}, {}, {}
    if ids:
        for session_id, st, how in db.execute(select(Attendance.session_id, Attendance.status, Attendance.method).where(Attendance.session_id.in_(ids), Attendance.student_id == student_id)):
            status[session_id], method[session_id] = st, how
        for session_id, request_kind, request_status, count in db.execute(select(FocusExitRequest.session_id, FocusExitRequest.kind, FocusExitRequest.status, func.count())
                .where(FocusExitRequest.session_id.in_(ids), FocusExitRequest.student_id == student_id).group_by(FocusExitRequest.session_id, FocusExitRequest.kind, FocusExitRequest.status)):
            if request_kind == "exit":
                exits[session_id] = exits.get(session_id, 0) + count
                if request_status == "approved":
                    approved[session_id] = approved.get(session_id, 0) + count
            else:
                resumes[session_id] = resumes.get(session_id, 0) + count
        for session_id, action, count in db.execute(select(FocusEvent.session_id, FocusEvent.action, func.count())
                .where(FocusEvent.session_id.in_(ids), FocusEvent.student_id == student_id).group_by(FocusEvent.session_id, FocusEvent.action)):
            if action == "exited":
                quits[session_id] = count
            elif action == "backgrounded":
                interruptions[session_id] = count
    history = [{"id": row.id, "title": row.title, "starts_at": utc(row.starts_at), "room_code": row.room_code, "status": status.get(row.id), "method": method.get(row.id),
                "breaks": exits.get(row.id, 0), "quit_focus": quits.get(row.id, 0), "interruptions": interruptions.get(row.id, 0)} for row in held]
    timeline = [{"starts_at": row.starts_at, "present": 1 if status.get(row.id) == "present" else 0, "expected": 1,
                 "quit_focus": quits.get(row.id, 0), "breaks": exits.get(row.id, 0)} for row in held]
    present = sum(1 for row in held if status.get(row.id) == "present")
    totals = {"held": len(held), "present": present, "absent": sum(1 for v in status.values() if v == "absent"), "excused": sum(1 for v in status.values() if v == "excused"),
              "not_recorded": sum(1 for row in held if row.id not in status), "rate": pct(present, len(held)),
              "breaks": sum(exits.values()), "breaks_approved": sum(approved.values()), "resume_requests": sum(resumes.values()),
              "quit_focus": sum(quits.values()), "interruptions": sum(interruptions.values()),
              "via_qr": sum(1 for row in held if status.get(row.id) == "present" and method.get(row.id) == "qr"),
              "via_code": sum(1 for row in held if status.get(row.id) == "present" and method.get(row.id) == "code")}
    return {"course": {"id": course.id, "code": course.code, "title": course.title}, "student": {"id": person.id, "name": person.name, "email": person.email, "kind": kind},
            "totals": totals, "weekly": periods(timeline, "week"), "monthly": periods(timeline, "month"), "history": history}
