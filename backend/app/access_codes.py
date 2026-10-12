"""Backup class codes for a teacher with no phone or laptop in class.

The teacher sets an "enter" code (students check in with it instead of scanning the rotating QR) and an "exit" code (students
leave focus early with it instead of the teacher's QR or approval). Unlike the QR these do NOT rotate, so a code shared outside the
room can be used by someone who is not there. They are therefore recorded as a separate, lower-assurance method ("code"), only work
inside the class window, are rate limited per student, and can be changed or cleared by the teacher at any time."""
import hashlib
import hmac
from datetime import datetime, timedelta, timezone
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session
from .auth import current_user, teacher, utc
from .config import settings
from .db import get_db
from .models import AdminAudit, Attendance, AttendanceEvent, SessionCode, User

router = APIRouter()
EARLY = timedelta(minutes=10)


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class CodesInput(Strict):
    enter_code: str | None = Field(default=None, min_length=6, max_length=40)
    exit_code: str | None = Field(default=None, min_length=6, max_length=40)


class CodeEntry(Strict):
    code: str = Field(min_length=1, max_length=40)


def normal(code: str) -> str:
    return "".join(code.split()).lower()


def digest(kind: str, session_id: int, code: str) -> str:
    key = settings().qr_signing_key.encode() or b"unconfigured"
    return hmac.new(key, f"class-code|{kind}|{session_id}|{normal(code)}".encode(), hashlib.sha256).hexdigest()


def weak(code: str) -> bool:
    return len(set(normal(code))) < 3


def status_view(row: SessionCode | None) -> dict:
    return {"enter_set": bool(row and row.enter_digest), "exit_set": bool(row and row.exit_digest), "updated_at": utc(row.updated_at) if row else None}


@router.get("/sessions/{session_id}/access-codes")
def codes_status(session_id: int, user: User = Depends(teacher), db: Session = Depends(get_db)):
    from .main import visible_session
    visible_session(db, user, session_id)
    return status_view(db.get(SessionCode, session_id))


@router.put("/sessions/{session_id}/access-codes")
def set_codes(session_id: int, body: CodesInput, user: User = Depends(teacher), db: Session = Depends(get_db)):
    from .main import visible_session
    from .stage6 import rate_limit
    session = visible_session(db, user, session_id)
    if body.enter_code is None and body.exit_code is None:
        raise HTTPException(422, "Give an enter code, an exit code, or both")
    if session.state == "ended":
        raise HTTPException(409, "This session has ended")
    for code in (body.enter_code, body.exit_code):
        if code is not None and weak(code):
            raise HTTPException(422, "Choose a code that is harder to guess")
    if body.enter_code and body.exit_code and normal(body.enter_code) == normal(body.exit_code):
        raise HTTPException(422, "The enter and exit codes must be different")
    rate_limit(db, "access-codes", str(user.id), 30, 3600)
    row = db.get(SessionCode, session_id)
    if row is None:
        row = SessionCode(session_id=session_id, updated_by=user.id, updated_at=datetime.now(timezone.utc))
        db.add(row)
    if body.enter_code is not None:
        row.enter_digest = digest("enter", session_id, body.enter_code)
    if body.exit_code is not None:
        row.exit_digest = digest("exit", session_id, body.exit_code)
    if row.enter_digest and row.exit_digest and row.enter_digest == digest("enter", session_id, body.exit_code or ""):
        raise HTTPException(422, "The enter and exit codes must be different")
    row.updated_by, row.updated_at = user.id, datetime.now(timezone.utc)
    db.add(AdminAudit(actor_id=user.id, action="access_codes_set", target_type="session", target_id=str(session_id),
                      reason=f"Set {'enter' if body.enter_code else ''}{' and ' if body.enter_code and body.exit_code else ''}{'exit' if body.exit_code else ''} code",
                      recorded_at=datetime.now(timezone.utc)))
    db.commit()
    return status_view(row)


@router.delete("/sessions/{session_id}/access-codes", status_code=204)
def clear_codes(session_id: int, user: User = Depends(teacher), db: Session = Depends(get_db)):
    from .main import visible_session
    visible_session(db, user, session_id)
    row = db.get(SessionCode, session_id)
    if row is not None:
        db.delete(row)
        db.add(AdminAudit(actor_id=user.id, action="access_codes_cleared", target_type="session", target_id=str(session_id),
                          reason="Cleared the backup codes", recorded_at=datetime.now(timezone.utc)))
        db.commit()


def check(db: Session, user: User, session_id: int, kind: str, code: str):
    """Rate limit every attempt (the failed ones are committed so they count), then compare in constant time."""
    from .stage2 import enrolled_session, student_only
    from .stage6 import rate_limit
    student_only(user)
    session = enrolled_session(db, user, session_id)
    rate_limit(db, f"code-{kind}", f"{user.id}:{session_id}", 10, 300)
    row = db.get(SessionCode, session_id)
    expected = getattr(row, f"{kind}_digest", None) if row else None
    if not expected or not hmac.compare_digest(expected, digest(kind, session_id, code)):
        db.commit()
        raise HTTPException(403, "That code is not right, or the teacher has not set one for this class")
    return session


@router.post("/sessions/{session_id}/check-in/code")
def check_in_with_code(session_id: int, body: CodeEntry, user: User = Depends(current_user), db: Session = Depends(get_db)):
    session = check(db, user, session_id, "enter", body.code)
    now = datetime.now(timezone.utc)
    if session.state == "ended" or now < utc(session.starts_at) - EARLY or now > utc(session.ends_at):
        db.commit()
        raise HTTPException(409, "Class codes only work during the class window")
    existing = db.get(Attendance, (session_id, user.id))
    if existing is not None:
        db.commit()
        return {"status": existing.status, "method": existing.method, "duplicate": True}
    db.add(Attendance(session_id=session_id, student_id=user.id, course_id=session.course_id, status="present", method="code",
                      face_verified=False, confirmed_by=None, updated_at=now))
    db.add(AttendanceEvent(session_id=session_id, student_id=user.id, actor_id=user.id, action="code_checkin", previous_status=None,
                           new_status="present", reason="Checked in with the teacher's class code (lower assurance than QR)", recorded_at=now))
    db.commit()
    return {"status": "present", "method": "code", "duplicate": False}


@router.post("/sessions/{session_id}/focus-exit/code")
def exit_with_code(session_id: int, body: CodeEntry, user: User = Depends(current_user), db: Session = Depends(get_db)):
    from .stage3 import require_new_face_check
    session = check(db, user, session_id, "exit", body.code)
    require_new_face_check(db, session.id, user.id)
    db.commit()
    return {"approved": True}
