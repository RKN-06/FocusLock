"""Teacher-only deletion of class sessions, with every dependent record, a required reason and an audit entry."""
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session
from .auth import teacher
from .db import get_db
from .models import (AdminAudit, Attendance, AttendanceEvent, BleCredential, BleSighting, ClassSession, CourseResource,
                     DataRequest, DeviceChallenge, FocusEvent, FocusExitRequest, PendingAttendance, SessionCode, User)

router = APIRouter()
RUNNING = ("checkin_pending", "active", "paused")


class DeleteReason(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: str = Field(min_length=10, max_length=500)


def purge_sessions(db: Session, ids: list[int]) -> None:
    """Children first, so foreign keys hold. Student privacy requests are kept and simply detached from the session."""
    if not ids:
        return
    credentials = select(BleCredential.id).where(BleCredential.session_id.in_(ids))
    db.execute(delete(BleSighting).where(BleSighting.credential_id.in_(credentials)))
    for model in (BleCredential, CourseResource, FocusEvent, FocusExitRequest, PendingAttendance, AttendanceEvent, Attendance, DeviceChallenge, SessionCode):
        db.execute(delete(model).where(model.session_id.in_(ids)))
    db.execute(update(DataRequest).where(DataRequest.session_id.in_(ids)).values(session_id=None))
    db.execute(delete(ClassSession).where(ClassSession.id.in_(ids)))


def audit(db: Session, user: User, action: str, target_type: str, target_id: str, reason: str) -> None:
    db.add(AdminAudit(actor_id=user.id, action=action, target_type=target_type, target_id=target_id,
                      reason=reason.strip(), recorded_at=datetime.now(timezone.utc)))


@router.delete("/sessions/{session_id}")
def delete_session(session_id: int, body: DeleteReason, user: User = Depends(teacher), db: Session = Depends(get_db)):
    from .main import visible_session
    from .stage6 import rate_limit
    session = visible_session(db, user, session_id)
    if session.state in RUNNING:
        raise HTTPException(409, "End the session before deleting it")
    rate_limit(db, "session-delete", str(user.id), 30, 3600)
    purge_sessions(db, [session.id])
    audit(db, user, "session_deleted", "session", str(session_id), body.reason)
    db.commit()
    return {"deleted": 1}


@router.delete("/courses/{course_id}/sessions")
def delete_course_sessions(course_id: int, body: DeleteReason, user: User = Depends(teacher), db: Session = Depends(get_db)):
    from .main import visible_course
    from .stage6 import rate_limit
    visible_course(db, user, course_id)
    rows = db.scalars(select(ClassSession).where(ClassSession.course_id == course_id)).all()
    if any(row.state in RUNNING for row in rows):
        raise HTTPException(409, "End the running session before deleting all sessions")
    rate_limit(db, "session-delete", str(user.id), 30, 3600)
    purge_sessions(db, [row.id for row in rows])
    audit(db, user, "course_sessions_deleted", "course", str(course_id), f"{len(rows)} sessions: {body.reason.strip()}"[:500])
    db.commit()
    return {"deleted": len(rows)}
