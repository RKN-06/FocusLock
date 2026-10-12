"""Term-wise attendance reports: a student's own courses and calendar, and a teacher's day-by-day absences across their courses."""
from collections import defaultdict
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from .auth import current_user, teacher, utc
from .db import get_db
from .models import Attendance, ClassSession, CourseOffering, Enrollment, FocusEvent, FocusExitRequest, User

router = APIRouter()
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def pct(part: int, whole: int) -> float | None:
    return round(100.0 * part / whole, 1) if whole else None


def local_date(value) -> str:
    from .slots import campus_zone
    return value.astimezone(campus_zone()).date().isoformat()


def latest_first(terms: dict[str, object]) -> list[str]:
    return sorted(terms, key=lambda name: terms[name], reverse=True)


@router.get("/attendance/me/summary")
def my_summary(user: User = Depends(current_user), db: Session = Depends(get_db)):
    """For a student: every term, each course in it, and each held class with the student's result, ready for a calendar."""
    if user.role != "student":
        raise HTTPException(403, "Student role required")
    courses = db.execute(select(CourseOffering, User.name).join(Enrollment, Enrollment.course_id == CourseOffering.id)
                         .join(User, User.id == CourseOffering.teacher_id).where(Enrollment.student_id == user.id)).all()
    ids = [course.id for course, _ in courses]
    sessions = db.scalars(select(ClassSession).where(ClassSession.course_id.in_(ids), ClassSession.state != "ready").order_by(ClassSession.starts_at, ClassSession.id)).all() if ids else []
    session_ids = [row.id for row in sessions]
    status: dict[int, str] = {}
    quits: dict[int, int] = defaultdict(int)
    breaks: dict[int, int] = defaultdict(int)
    if session_ids:
        status = dict(db.execute(select(Attendance.session_id, Attendance.status).where(Attendance.student_id == user.id, Attendance.session_id.in_(session_ids))).all())
        for session_id, count in db.execute(select(FocusEvent.session_id, func.count()).where(FocusEvent.student_id == user.id, FocusEvent.action == "exited",
                                                                                          FocusEvent.session_id.in_(session_ids)).group_by(FocusEvent.session_id)):
            quits[session_id] = count
        for session_id, count in db.execute(select(FocusExitRequest.session_id, func.count()).where(FocusExitRequest.student_id == user.id, FocusExitRequest.kind == "exit",
                                                                                                  FocusExitRequest.session_id.in_(session_ids)).group_by(FocusExitRequest.session_id)):
            breaks[session_id] = count
    by_course: dict[int, list[ClassSession]] = defaultdict(list)
    for row in sessions:
        by_course[row.course_id].append(row)
    terms: dict[str, list[dict]] = defaultdict(list)
    recency: dict[str, object] = {}
    for course, teacher_name in courses:
        rows = by_course.get(course.id, [])
        present = sum(1 for row in rows if status.get(row.id) == "present")
        excused = sum(1 for row in rows if status.get(row.id) == "excused")
        absent = sum(1 for row in rows if status.get(row.id) == "absent")
        item = {"course_id": course.id, "code": course.code, "title": course.title, "teacher": teacher_name, "held": len(rows), "present": present,
                "absent": absent, "excused": excused, "not_recorded": len(rows) - present - excused - absent, "rate": pct(present, len(rows)),
                "quit_focus": sum(quits[row.id] for row in rows), "breaks": sum(breaks[row.id] for row in rows),
                "days": [{"session_id": row.id, "title": row.title, "date": local_date(row.starts_at), "starts_at": utc(row.starts_at), "room_code": row.room_code,
                          "status": status.get(row.id)} for row in rows]}
        terms[course.term].append(item)
        newest = utc(rows[-1].starts_at) if rows else EPOCH
        recency[course.term] = max(recency.get(course.term, EPOCH), newest)
    return {"terms": [{"term": name, "courses": sorted(terms[name], key=lambda row: row["code"])} for name in latest_first(recency)]}


@router.get("/reports/teacher")
def teacher_report(term: str | None = None, user: User = Depends(teacher), db: Session = Depends(get_db)):
    """For a teacher: terms, a summary per course, and for every held class the number present and absent, so absences are visible day by day."""
    all_courses = db.scalars(select(CourseOffering).where(CourseOffering.teacher_id == user.id).order_by(CourseOffering.code)).all()
    ids = [course.id for course in all_courses]
    sessions = db.scalars(select(ClassSession).where(ClassSession.course_id.in_(ids), ClassSession.state != "ready").order_by(ClassSession.starts_at, ClassSession.id)).all() if ids else []
    recency: dict[str, object] = {course.term: EPOCH for course in all_courses}
    term_of = {course.id: course.term for course in all_courses}
    for row in sessions:
        recency[term_of[row.course_id]] = max(recency[term_of[row.course_id]], utc(row.starts_at))
    terms = latest_first(recency)
    chosen = term if term in recency else (terms[0] if terms else None)
    courses = [course for course in all_courses if course.term == chosen]
    wanted = {course.id for course in courses}
    sessions = [row for row in sessions if row.course_id in wanted]
    students = dict(db.execute(select(Enrollment.course_id, func.count()).where(Enrollment.course_id.in_(wanted)).group_by(Enrollment.course_id)).all()) if wanted else {}
    counts: dict[int, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    if sessions:
        for session_id, st, count in db.execute(select(Attendance.session_id, Attendance.status, func.count()).where(Attendance.session_id.in_([row.id for row in sessions]))
                                                .group_by(Attendance.session_id, Attendance.status)):
            counts[session_id][st] = count
    codes = {course.id: course.code for course in courses}
    daily, per_course = [], defaultdict(lambda: {"held": 0, "present": 0, "expected": 0, "excused": 0})
    for row in sessions:
        expected, present, excused = students.get(row.course_id, 0), counts[row.id]["present"], counts[row.id]["excused"]
        absent = max(0, expected - present - excused)
        daily.append({"date": local_date(row.starts_at), "starts_at": utc(row.starts_at), "course_id": row.course_id, "code": codes[row.course_id], "session_id": row.id,
                      "title": row.title, "room_code": row.room_code, "expected": expected, "present": present, "excused": excused, "absent": absent, "rate": pct(present, expected)})
        total = per_course[row.course_id]
        total["held"] += 1
        total["present"] += present
        total["excused"] += excused
        total["expected"] += expected
    summary = [{"id": course.id, "code": course.code, "title": course.title, "term": course.term, "students": students.get(course.id, 0), "held": per_course[course.id]["held"],
                "present": per_course[course.id]["present"], "absent": max(0, per_course[course.id]["expected"] - per_course[course.id]["present"] - per_course[course.id]["excused"]),
                "rate": pct(per_course[course.id]["present"], per_course[course.id]["expected"])} for course in courses]
    return {"terms": terms, "term": chosen, "courses": summary, "daily": daily}
