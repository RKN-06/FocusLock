"""Administrators give a teacher many courses at once."""
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from .auth import admin
from .db import get_db
from .groups import course_detail, set_course_groups
from .models import AdminAudit, CourseOffering, StudentGroup, User

router = APIRouter()
MAX_COURSES = 50


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class CourseRow(Strict):
    code: str = Field(min_length=1, max_length=32)
    title: str = Field(min_length=1, max_length=150)


class BulkCourses(Strict):
    teacher_id: int = Field(gt=0)
    term: str = Field(min_length=1, max_length=40)
    courses: list[CourseRow] = Field(min_length=1, max_length=MAX_COURSES)
    # Student groups (for example "CSE - Year 3") attending every course in this request; their members are enrolled automatically.
    group_ids: list[int] = Field(default_factory=list, max_length=20)


def course_view(row: CourseOffering) -> dict:
    return {"id": row.id, "code": row.code, "title": row.title, "term": row.term, "teacher_id": row.teacher_id}


def teacher_or_404(db: Session, teacher_id: int) -> User:
    teacher = db.get(User, teacher_id)
    if teacher is None or teacher.role != "teacher":
        raise HTTPException(404, "Teacher not found")
    return teacher


@router.get("/admin/teachers")
def list_teachers(user: User = Depends(admin), db: Session = Depends(get_db)):
    counts = dict(db.execute(select(CourseOffering.teacher_id, func.count()).group_by(CourseOffering.teacher_id)).all())
    rows = db.scalars(select(User).where(User.role == "teacher").order_by(User.name, User.id))
    return [{"id": row.id, "name": row.name, "email": row.email, "courses": counts.get(row.id, 0)} for row in rows]


@router.get("/admin/teachers/{teacher_id}/courses")
def teacher_courses(teacher_id: int, user: User = Depends(admin), db: Session = Depends(get_db)):
    teacher_or_404(db, teacher_id)
    rows = db.scalars(select(CourseOffering).where(CourseOffering.teacher_id == teacher_id).order_by(CourseOffering.term, CourseOffering.code))
    return [course_detail(db, row) for row in rows]


@router.post("/admin/courses/bulk", status_code=201)
def add_courses(body: BulkCourses, user: User = Depends(admin), db: Session = Depends(get_db)):
    """Create every listed course for the teacher. A course already on that teacher's list for the term is skipped, not duplicated."""
    from .stage6 import rate_limit
    teacher = teacher_or_404(db, body.teacher_id)
    rate_limit(db, "course-bulk", str(user.id), 10, 3600)
    term = body.term.strip()
    if body.group_ids:
        found = set(db.scalars(select(StudentGroup.id).where(StudentGroup.id.in_(set(body.group_ids)))))
        if found != set(body.group_ids):
            raise HTTPException(404, "One of the groups was not found")
    have = {row.lower() for row in db.scalars(select(CourseOffering.code).where(CourseOffering.teacher_id == teacher.id, CourseOffering.term == term))}
    created, skipped = [], []
    for item in body.courses:
        code, title = item.code.strip(), item.title.strip()
        if not code or not title:
            skipped.append({"code": code or "(blank)", "reason": "Needs both a code and a title"})
        elif code.lower() in have:
            skipped.append({"code": code, "reason": "Already on this teacher's list for that term"})
        else:
            row = CourseOffering(code=code, title=title, term=term, teacher_id=teacher.id)
            db.add(row)
            created.append(row)
            have.add(code.lower())
    db.flush()
    enrolled = 0
    for row in created:
        if body.group_ids:
            enrolled += set_course_groups(db, row, body.group_ids)["added"]
    if created:
        db.add(AdminAudit(actor_id=user.id, action="courses_bulk_created", target_type="teacher", target_id=str(teacher.id),
                          reason=f"Added {len(created)} courses for {term} for {len(set(body.group_ids))} groups"[:500], recorded_at=datetime.now(timezone.utc)))
    db.commit()
    return {"created": [course_detail(db, row) for row in created], "skipped": skipped, "enrolled": enrolled}
