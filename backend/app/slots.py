"""Class time slots (administrator-managed) and bulk scheduling of sessions from them."""
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session
from .auth import admin, current_user, teacher, utc
from .config import settings
from .db import get_db
from .models import AdminAudit, ClassSession, CourseOffering, TimeSlot, User

router = APIRouter()
HHMM = r"^([01]\d|2[0-3]):[0-5]\d$"
MAX_PER_REQUEST = 100


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SlotInput(Strict):
    label: str = Field(min_length=1, max_length=60)
    starts: str = Field(pattern=HHMM)
    ends: str = Field(pattern=HHMM)
    active: bool = True


class BulkSessions(Strict):
    course_id: int = Field(gt=0)
    title: str = Field(min_length=3, max_length=150)
    room_code: str = Field(default="UNASSIGNED", min_length=1, max_length=40, pattern=r"^[A-Za-z0-9._-]+$")
    dates: list[date] = Field(min_length=1, max_length=62)
    slot_ids: list[int] = Field(min_length=1, max_length=12)


def hhmm(value: str) -> time:
    hour, minute = value.split(":")
    return time(int(hour), int(minute))


def slot_view(row: TimeSlot) -> dict:
    return {"id": row.id, "label": row.label, "starts": row.starts_at.strftime("%H:%M"), "ends": row.ends_at.strftime("%H:%M"), "active": row.active}


def campus_zone() -> ZoneInfo:
    try:
        return ZoneInfo(settings().campus_timezone)
    except Exception:
        raise HTTPException(500, "The campus timezone is not configured correctly")


def listing(db: Session, only_active: bool) -> dict:
    query = select(TimeSlot).order_by(TimeSlot.starts_at, TimeSlot.id)
    if only_active:
        query = query.where(TimeSlot.active.is_(True))
    return {"timezone": settings().campus_timezone, "slots": [slot_view(row) for row in db.scalars(query)]}


def audit(db: Session, user: User, action: str, target: str, reason: str) -> None:
    db.add(AdminAudit(actor_id=user.id, action=action, target_type="time_slot", target_id=target, reason=reason[:500],
                      recorded_at=datetime.now(timezone.utc)))


def check_slot(db: Session, body: SlotInput, ignore_id: int | None = None) -> tuple[time, time]:
    start, end = hhmm(body.starts), hhmm(body.ends)
    if end <= start:
        raise HTTPException(422, "A slot must end after it starts")
    if (datetime.combine(date.min, end) - datetime.combine(date.min, start)) > timedelta(hours=8):
        raise HTTPException(422, "A slot can be at most eight hours long")
    label = body.label.strip()
    other = db.scalar(select(TimeSlot.id).where(TimeSlot.label == label, TimeSlot.id != (ignore_id or 0)))
    if other is not None:
        raise HTTPException(409, "A slot with this name already exists")
    if body.active:
        clash = db.scalar(select(TimeSlot).where(TimeSlot.active.is_(True), TimeSlot.id != (ignore_id or 0),
                                                 TimeSlot.starts_at < end, TimeSlot.ends_at > start))
        if clash is not None:
            raise HTTPException(409, f"This slot overlaps {clash.label} ({clash.starts_at:%H:%M}-{clash.ends_at:%H:%M})")
    return start, end


@router.get("/slots")
def list_slots(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return listing(db, only_active=True)


@router.get("/admin/slots")
def admin_list_slots(user: User = Depends(admin), db: Session = Depends(get_db)):
    return listing(db, only_active=False)


@router.post("/admin/slots", status_code=201)
def create_slot(body: SlotInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    start, end = check_slot(db, body)
    row = TimeSlot(label=body.label.strip(), starts_at=start, ends_at=end, active=body.active,
                   updated_by=user.id, updated_at=datetime.now(timezone.utc))
    db.add(row)
    db.flush()
    audit(db, user, "slot_created", str(row.id), f"{row.label} {body.starts}-{body.ends}")
    db.commit()
    return slot_view(row)


@router.put("/admin/slots/{slot_id}")
def update_slot(slot_id: int, body: SlotInput, user: User = Depends(admin), db: Session = Depends(get_db)):
    row = db.get(TimeSlot, slot_id)
    if row is None:
        raise HTTPException(404, "Slot not found")
    start, end = check_slot(db, body, ignore_id=slot_id)
    row.label, row.starts_at, row.ends_at, row.active = body.label.strip(), start, end, body.active
    row.updated_by, row.updated_at = user.id, datetime.now(timezone.utc)
    audit(db, user, "slot_updated", str(slot_id), f"{row.label} {body.starts}-{body.ends} active={body.active}")
    db.commit()
    return slot_view(row)


@router.delete("/admin/slots/{slot_id}", status_code=204)
def delete_slot(slot_id: int, user: User = Depends(admin), db: Session = Depends(get_db)):
    """Sessions already scheduled keep their own times, so removing a slot never changes them."""
    row = db.get(TimeSlot, slot_id)
    if row is None:
        raise HTTPException(404, "Slot not found")
    audit(db, user, "slot_deleted", str(slot_id), f"{row.label} {row.starts_at:%H:%M}-{row.ends_at:%H:%M}")
    db.delete(row)
    db.commit()


@router.post("/sessions/bulk", status_code=201)
def create_sessions_bulk(body: BulkSessions, user: User = Depends(teacher), db: Session = Depends(get_db)):
    """Schedule one session for every chosen date and every chosen slot. Clashes are skipped and reported, not fatal."""
    from .main import session_view, visible_course
    from .stage6 import rate_limit
    course = visible_course(db, user, body.course_id)
    wanted = set(body.slot_ids)
    slots = db.scalars(select(TimeSlot).where(TimeSlot.id.in_(wanted), TimeSlot.active.is_(True)).order_by(TimeSlot.starts_at)).all()
    if len(slots) != len(wanted):
        raise HTTPException(422, "Choose time slots that are currently active")
    dates = sorted(set(body.dates))
    if len(dates) * len(slots) > MAX_PER_REQUEST:
        raise HTTPException(422, f"Schedule at most {MAX_PER_REQUEST} sessions at a time")
    rate_limit(db, "session-bulk", str(user.id), 10, 3600)
    zone = campus_zone()
    now = datetime.now(timezone.utc)
    plan = []
    for day in dates:
        for slot in slots:
            start = datetime.combine(day, slot.starts_at, tzinfo=zone).astimezone(timezone.utc)
            end = datetime.combine(day, slot.ends_at, tzinfo=zone).astimezone(timezone.utc)
            plan.append((day, slot, start, end))
    existing = db.execute(select(ClassSession.course_id, ClassSession.starts_at, ClassSession.ends_at)
                          .join(CourseOffering, CourseOffering.id == ClassSession.course_id)
                          .where(CourseOffering.teacher_id == user.id,
                                 ClassSession.starts_at < max(p[3] for p in plan), ClassSession.ends_at > min(p[2] for p in plan))).all()
    existing = [(course_id, utc(starts), utc(ends)) for course_id, starts, ends in existing]
    created, skipped = [], []
    for day, slot, start, end in plan:
        reason = None
        if start < now - timedelta(minutes=5):
            reason = "Already started or in the past"
        elif start > now + timedelta(days=365):
            reason = "More than a year ahead"
        else:
            for course_id, other_start, other_end in existing:
                if other_start < end and other_end > start:
                    reason = "This course already has a session then" if course_id == course.id else "You already teach another session then"
                    break
        if reason:
            skipped.append({"date": day.isoformat(), "slot": slot.label, "reason": reason})
            continue
        row = ClassSession(course_id=course.id, title=body.title.strip(), starts_at=start, ends_at=end, state="ready", room_code=body.room_code)
        db.add(row)
        existing.append((course.id, start, end))
        created.append(row)
    db.flush()
    if created:
        db.add(AdminAudit(actor_id=user.id, action="sessions_bulk_created", target_type="course", target_id=str(course.id),
                          reason=f"Teacher scheduled {len(created)} classes from {len(slots)} slots"[:500], recorded_at=now))
    db.commit()
    return {"created": [session_view(db, user, row) for row in created], "skipped": skipped}
