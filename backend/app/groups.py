"""Student groups (cohorts) managed by administrators. Linking a group to a course enrols every member automatically."""
from datetime import datetime, timezone
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session
from .auth import admin
from .db import get_db
from .models import AdminAudit, Attendance, CourseGroup, CourseOffering, Enrollment, GroupMember, StudentGroup, User

router = APIRouter()


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class GroupInput(Strict):
    name: str = Field(min_length=1, max_length=80)


Kind = Literal["regular", "reregistered", "other"]
RANK = {"regular": 0, "other": 1, "reregistered": 2}


class MemberEntry(Strict):
    email: str = Field(min_length=1, max_length=254)
    kind: Kind = "regular"


class MembersInput(Strict):
    """Either plain addresses (all given `kind`) or entries that each carry their own kind."""
    emails: list[str] = Field(default_factory=list, max_length=500)
    members: list[MemberEntry] = Field(default_factory=list, max_length=500)
    kind: Kind = "regular"


class KindInput(Strict):
    kind: Kind


class CourseStudentsInput(Strict):
    students: list[MemberEntry] = Field(min_length=1, max_length=500)


class CourseGroupsInput(Strict):
    group_ids: list[int] = Field(max_length=20)


def audit(db: Session, user: User, action: str, target_type: str, target_id: str, reason: str) -> None:
    db.add(AdminAudit(actor_id=user.id, action=action, target_type=target_type, target_id=target_id,
                      reason=reason[:500], recorded_at=datetime.now(timezone.utc)))


def sync_course(db: Session, course_id: int) -> dict:
    """Make the course's group-sourced enrolments match its groups' members.

    Members are added. A group-sourced enrolment is removed only when nobody linked still covers the student and the student has no
    attendance recorded in the course; otherwise it is kept and counted. Manual enrolments are never touched."""
    desired: dict[int, str] = {}
    for student_id, kind in db.execute(select(GroupMember.student_id, GroupMember.kind).join(CourseGroup, CourseGroup.group_id == GroupMember.group_id)
                                       .where(CourseGroup.course_id == course_id)):
        if student_id not in desired or RANK.get(kind, 0) > RANK[desired[student_id]]:
            desired[student_id] = kind  # in several linked groups, the most specific registration type wins
    rows = {row.student_id: row for row in db.scalars(select(Enrollment).where(Enrollment.course_id == course_id))}
    added = removed = kept = 0
    for student_id in set(desired) - set(rows):
        db.add(Enrollment(course_id=course_id, student_id=student_id, source="group", kind=desired[student_id]))
        added += 1
    for student_id, row in rows.items():
        if row.source != "group":
            continue
        if student_id in desired:
            row.kind = desired[student_id]
            continue
        has_attendance = db.scalar(select(func.count()).select_from(Attendance).where(Attendance.course_id == course_id, Attendance.student_id == student_id))
        if has_attendance:
            kept += 1
        else:
            db.delete(row)
            removed += 1
    db.flush()
    return {"added": added, "removed": removed, "kept": kept}


def linked_courses(db: Session, group_id: int) -> list[int]:
    return list(db.scalars(select(CourseGroup.course_id).where(CourseGroup.group_id == group_id)))


def course_groups(db: Session, course_id: int) -> list[dict]:
    rows = db.execute(select(StudentGroup.id, StudentGroup.name, func.count(GroupMember.student_id))
                      .join(CourseGroup, CourseGroup.group_id == StudentGroup.id)
                      .outerjoin(GroupMember, GroupMember.group_id == StudentGroup.id)
                      .where(CourseGroup.course_id == course_id).group_by(StudentGroup.id, StudentGroup.name).order_by(StudentGroup.name)).all()
    return [{"id": gid, "name": name, "members": count} for gid, name, count in rows]


def course_detail(db: Session, row: CourseOffering) -> dict:
    students = db.scalar(select(func.count()).select_from(Enrollment).where(Enrollment.course_id == row.id)) or 0
    return {"id": row.id, "code": row.code, "title": row.title, "term": row.term, "teacher_id": row.teacher_id,
            "groups": course_groups(db, row.id), "students": students}


def group_or_404(db: Session, group_id: int) -> StudentGroup:
    group = db.get(StudentGroup, group_id)
    if group is None:
        raise HTTPException(404, "Group not found")
    return group


def set_course_groups(db: Session, course: CourseOffering, group_ids: list[int]) -> dict:
    wanted = set(group_ids)
    found = set(db.scalars(select(StudentGroup.id).where(StudentGroup.id.in_(wanted)))) if wanted else set()
    if found != wanted:
        raise HTTPException(404, "One of the groups was not found")
    db.execute(delete(CourseGroup).where(CourseGroup.course_id == course.id))
    for group_id in wanted:
        db.add(CourseGroup(course_id=course.id, group_id=group_id))
    db.flush()
    return sync_course(db, course.id)


@router.get("/admin/groups")
def list_groups(user: User = Depends(admin), db: Session = Depends(get_db)):
    members = dict(db.execute(select(GroupMember.group_id, func.count()).group_by(GroupMember.group_id)).all())
    courses = dict(db.execute(select(CourseGroup.group_id, func.count()).group_by(CourseGroup.group_id)).all())
    return [{"id": g.id, "name": g.name, "members": members.get(g.id, 0), "courses": courses.get(g.id, 0)}
            for g in db.scalars(select(StudentGroup).order_by(StudentGroup.name))]


@router.post("/admin/groups", status_code=201)
def create_group(body: GroupInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    name = body.name.strip()
    if not name:
        raise HTTPException(422, "A group needs a name")
    if db.scalar(select(StudentGroup.id).where(func.lower(StudentGroup.name) == name.lower())) is not None:
        raise HTTPException(409, "A group with this name already exists")
    group = StudentGroup(name=name, created_at=datetime.now(timezone.utc))
    db.add(group)
    db.flush()
    audit(db, user, "group_created", "group", str(group.id), name)
    db.commit()
    return {"id": group.id, "name": group.name, "members": 0, "courses": 0}


@router.put("/admin/groups/{group_id}")
def rename_group(group_id: int, body: GroupInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    group = group_or_404(db, group_id)
    name = body.name.strip()
    if not name:
        raise HTTPException(422, "A group needs a name")
    if db.scalar(select(StudentGroup.id).where(func.lower(StudentGroup.name) == name.lower(), StudentGroup.id != group_id)) is not None:
        raise HTTPException(409, "A group with this name already exists")
    audit(db, user, "group_renamed", "group", str(group_id), f"{group.name} -> {name}")
    group.name = name
    db.commit()
    return {"id": group.id, "name": group.name}


@router.delete("/admin/groups/{group_id}")
def delete_group(group_id: int, user: User = Depends(admin), db: Session = Depends(get_db)):
    group = group_or_404(db, group_id)
    courses = linked_courses(db, group_id)
    db.execute(delete(CourseGroup).where(CourseGroup.group_id == group_id))
    db.execute(delete(GroupMember).where(GroupMember.group_id == group_id))
    db.flush()
    summary = {"added": 0, "removed": 0, "kept": 0}
    for course_id in courses:
        result = sync_course(db, course_id)
        for key in summary:
            summary[key] += result[key]
    audit(db, user, "group_deleted", "group", str(group_id), group.name)
    db.delete(group)
    db.commit()
    return summary


@router.get("/admin/groups/{group_id}")
def group_detail(group_id: int, user: User = Depends(admin), db: Session = Depends(get_db)):
    group = group_or_404(db, group_id)
    members = db.scalars(select(User).join(GroupMember, GroupMember.student_id == User.id).where(GroupMember.group_id == group_id).order_by(User.name)).all()
    kinds = dict(db.execute(select(GroupMember.student_id, GroupMember.kind).where(GroupMember.group_id == group_id)).all())
    courses = db.execute(select(CourseOffering, User.name).join(User, User.id == CourseOffering.teacher_id)
                         .join(CourseGroup, CourseGroup.course_id == CourseOffering.id).where(CourseGroup.group_id == group_id)
                         .order_by(CourseOffering.code)).all()
    return {"id": group.id, "name": group.name,
            "members": [{"id": m.id, "name": m.name, "email": m.email, "kind": kinds.get(m.id, "regular")} for m in members],
            "courses": [{"id": c.id, "code": c.code, "title": c.title, "term": c.term, "teacher": teacher} for c, teacher in courses]}


@router.post("/admin/groups/{group_id}/members")
def add_members(group_id: int, body: MembersInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    """Add students by email. Unknown addresses and non-students are reported back instead of failing the whole list."""
    from .stage6 import rate_limit
    group_or_404(db, group_id)
    rate_limit(db, "group-members", str(user.id), 30, 3600)
    have = set(db.scalars(select(GroupMember.student_id).where(GroupMember.group_id == group_id)))
    added, already, unknown, not_student = [], [], [], []
    kinds: dict[str, str] = {}
    for email in body.emails:
        if email.strip():
            kinds.setdefault(email.strip().lower(), body.kind)
    for entry in body.members:
        if entry.email.strip():
            kinds[entry.email.strip().lower()] = entry.kind
    if not kinds:
        raise HTTPException(422, "Give at least one email address")
    for raw in kinds:
        person = db.scalar(select(User).where(func.lower(User.email) == raw))
        if person is None:
            unknown.append(raw)
        elif person.role != "student":
            not_student.append(raw)
        elif person.id in have:
            already.append(raw)
        else:
            db.add(GroupMember(group_id=group_id, student_id=person.id, kind=kinds[raw]))
            have.add(person.id)
            added.append(raw)
    db.flush()
    enrolled = {"added": 0, "removed": 0, "kept": 0}
    if added:
        for course_id in linked_courses(db, group_id):
            result = sync_course(db, course_id)
            enrolled["added"] += result["added"]
        audit(db, user, "group_members_added", "group", str(group_id), f"Added {len(added)} students")
    db.commit()
    return {"added": added, "already": already, "unknown": unknown, "not_student": not_student, "enrolled": enrolled["added"]}


@router.put("/admin/groups/{group_id}/members/{student_id}")
def set_member_kind(group_id: int, student_id: int, body: KindInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    group_or_404(db, group_id)
    member = db.get(GroupMember, (group_id, student_id))
    if member is None:
        raise HTTPException(404, "That student is not in this group")
    member.kind = body.kind
    db.flush()
    for course_id in linked_courses(db, group_id):
        sync_course(db, course_id)
    audit(db, user, "group_member_kind_set", "group", str(group_id), f"Student {student_id} is {body.kind}")
    db.commit()
    return {"id": student_id, "kind": body.kind}


@router.delete("/admin/groups/{group_id}/members/{student_id}")
def remove_member(group_id: int, student_id: int, user: User = Depends(admin), db: Session = Depends(get_db)):
    group_or_404(db, group_id)
    member = db.get(GroupMember, (group_id, student_id))
    if member is None:
        raise HTTPException(404, "That student is not in this group")
    db.delete(member)
    db.flush()
    summary = {"removed": 0, "kept": 0}
    for course_id in linked_courses(db, group_id):
        result = sync_course(db, course_id)
        summary["removed"] += result["removed"]
        summary["kept"] += result["kept"]
    audit(db, user, "group_member_removed", "group", str(group_id), f"Removed student {student_id}")
    db.commit()
    return summary


@router.get("/admin/students")
def list_students(user: User = Depends(admin), db: Session = Depends(get_db)):
    groups: dict[int, list[str]] = {}
    for student_id, name in db.execute(select(GroupMember.student_id, StudentGroup.name).join(StudentGroup, StudentGroup.id == GroupMember.group_id)):
        groups.setdefault(student_id, []).append(name)
    rows = db.scalars(select(User).where(User.role == "student").order_by(User.name, User.id).limit(5000))
    return [{"id": s.id, "name": s.name, "email": s.email, "groups": sorted(groups.get(s.id, []))} for s in rows]


@router.put("/admin/courses/{course_id}/groups")
def put_course_groups(course_id: int, body: CourseGroupsInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    course = db.get(CourseOffering, course_id)
    if course is None:
        raise HTTPException(404, "Course not found")
    result = set_course_groups(db, course, body.group_ids)
    audit(db, user, "course_groups_set", "course", str(course_id), f"{len(set(body.group_ids))} groups; {result['added']} enrolled, {result['removed']} removed, {result['kept']} kept")
    db.commit()
    return {**course_detail(db, course), "sync": result}


@router.get("/admin/courses/{course_id}/students")
def course_students(course_id: int, user: User = Depends(admin), db: Session = Depends(get_db)):
    if db.get(CourseOffering, course_id) is None:
        raise HTTPException(404, "Course not found")
    rows = db.execute(select(User, Enrollment).join(Enrollment, Enrollment.student_id == User.id).where(Enrollment.course_id == course_id).order_by(User.name, User.id)).all()
    return [{"id": u.id, "name": u.name, "email": u.email, "kind": e.kind, "source": e.source} for u, e in rows]


@router.post("/admin/courses/{course_id}/students")
def add_course_students(course_id: int, body: CourseStudentsInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    """Enrol individual students (for example reregistered ones) with a registration type. An existing enrolment just takes the new type."""
    from .stage6 import rate_limit
    if db.get(CourseOffering, course_id) is None:
        raise HTTPException(404, "Course not found")
    rate_limit(db, "course-students", str(user.id), 30, 3600)
    added, updated, unknown, not_student = [], [], [], []
    for entry in {e.email.strip().lower(): e for e in body.students if e.email.strip()}.values():
        raw = entry.email.strip().lower()
        person = db.scalar(select(User).where(func.lower(User.email) == raw))
        if person is None:
            unknown.append(raw)
            continue
        if person.role != "student":
            not_student.append(raw)
            continue
        row = db.get(Enrollment, (course_id, person.id))
        if row is None:
            db.add(Enrollment(course_id=course_id, student_id=person.id, source="manual", kind=entry.kind))
            added.append(raw)
        else:
            row.kind, row.source = entry.kind, "manual"  # set by hand, so group changes no longer move it
            updated.append(raw)
    if added or updated:
        audit(db, user, "course_students_set", "course", str(course_id), f"{len(added)} added, {len(updated)} updated")
    db.commit()
    return {"added": added, "updated": updated, "unknown": unknown, "not_student": not_student}


@router.put("/admin/courses/{course_id}/students/{student_id}")
def set_course_student_kind(course_id: int, student_id: int, body: KindInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    row = db.get(Enrollment, (course_id, student_id))
    if row is None:
        raise HTTPException(404, "That student is not enrolled")
    row.kind, row.source = body.kind, "manual"
    audit(db, user, "course_student_kind_set", "course", str(course_id), f"Student {student_id} is {body.kind}")
    db.commit()
    return {"id": student_id, "kind": body.kind}


@router.delete("/admin/courses/{course_id}/students/{student_id}")
def remove_course_student(course_id: int, student_id: int, user: User = Depends(admin), db: Session = Depends(get_db)):
    row = db.get(Enrollment, (course_id, student_id))
    if row is None:
        raise HTTPException(404, "That student is not enrolled")
    if row.source == "group":
        raise HTTPException(409, "This student comes from a linked group. Remove them from the group instead.")
    if db.scalar(select(func.count()).select_from(Attendance).where(Attendance.course_id == course_id, Attendance.student_id == student_id)):
        raise HTTPException(409, "This student has attendance in the course, so they stay enrolled")
    db.delete(row)
    audit(db, user, "course_student_removed", "course", str(course_id), f"Student {student_id}")
    db.commit()
    return {"removed": student_id}
