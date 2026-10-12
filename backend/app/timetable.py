"""A student's (or teacher's) smart timetable: classes in date order with room, teacher and period name."""
from datetime import date, datetime, time, timedelta, timezone
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session
from .auth import current_user, utc
from .db import get_db
from .groups import course_groups
from .models import Attendance, ClassSession, CourseOffering, Enrollment, TimeSlot, User

router = APIRouter()


@router.get("/timetable")
def timetable(start: date | None = None, days: int = Query(default=7, ge=1, le=31), user: User = Depends(current_user), db: Session = Depends(get_db)):
    from .slots import campus_zone
    if user.role not in ("student", "teacher"):
        raise HTTPException(403, "Timetables are for students and teachers")
    zone = campus_zone()
    first = start or datetime.now(zone).date()
    low = datetime.combine(first, time.min, tzinfo=zone).astimezone(timezone.utc)
    high = datetime.combine(first + timedelta(days=days), time.min, tzinfo=zone).astimezone(timezone.utc)
    query = (select(ClassSession, CourseOffering, User.name).join(CourseOffering, CourseOffering.id == ClassSession.course_id)
             .join(User, User.id == CourseOffering.teacher_id).where(ClassSession.starts_at >= low, ClassSession.starts_at < high))
    if user.role == "teacher":
        query = query.where(CourseOffering.teacher_id == user.id)
    else:
        query = query.join(Enrollment, Enrollment.course_id == CourseOffering.id).where(Enrollment.student_id == user.id)
    rows = db.execute(query.order_by(ClassSession.starts_at, ClassSession.id)).all()
    periods = {(slot.starts_at, slot.ends_at): slot.label for slot in db.scalars(select(TimeSlot))}
    status = {}
    if user.role == "student" and rows:
        ids = [session.id for session, _, _ in rows]
        status = {row.session_id: row.status for row in db.scalars(select(Attendance).where(Attendance.student_id == user.id, Attendance.session_id.in_(ids)))}
    group_names = {}
    entries = []
    for session, course, teacher_name in rows:
        begins, ends = utc(session.starts_at).astimezone(zone), utc(session.ends_at).astimezone(zone)
        if course.id not in group_names:
            group_names[course.id] = [g["name"] for g in course_groups(db, course.id)] if user.role == "teacher" else []
        entries.append({
            "id": session.id, "course_id": course.id, "course_code": course.code, "course_title": course.title, "teacher": teacher_name,
            "title": session.title, "room_code": session.room_code, "starts_at": utc(session.starts_at), "ends_at": utc(session.ends_at),
            "date": begins.date().isoformat(), "period": periods.get((begins.time().replace(second=0, microsecond=0), ends.time().replace(second=0, microsecond=0))),
            "state": session.state, "attendance": status.get(session.id), "groups": group_names[course.id],
        })
    return {"timezone": zone.key, "start": first.isoformat(), "days": days, "entries": entries}
