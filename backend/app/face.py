"""Staff-recorded face templates. Photos are never uploaded or stored; matching happens on the student's phone."""
import base64
import hashlib
import math
import os
import struct
from typing import Literal

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from .auth import current_user
from .config import settings
from .db import get_db
from .models import AdminAudit, CourseOffering, Enrollment, FaceTemplate, User
from .stage2 import now_utc, student_only

router = APIRouter()
MODEL = "sface-2021dec"
DIMENSIONS = 128


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class TemplateInput(Strict):
    template: list[float] = Field(min_length=DIMENSIONS, max_length=DIMENSIONS)
    model: Literal["sface-2021dec"]
    consent: Literal[True]


def mode() -> str:
    value = settings().face_check_mode
    if value not in ("off", "enrolled", "required"):
        raise HTTPException(503, "Face check mode is misconfigured")
    return value


def _key() -> bytes:
    secret = settings().face_template_key.encode()
    if len(secret) < 32:
        raise HTTPException(503, "Face template key is not configured")
    return hashlib.sha256(b"focuslock-face-template-v1|" + secret).digest()


def seal(student_id: int, template: list[float]) -> str:
    nonce = os.urandom(12)
    data = struct.pack(f"<{DIMENSIONS}f", *template)
    return base64.b64encode(nonce + AESGCM(_key()).encrypt(nonce, data, str(student_id).encode())).decode()


def unseal(student_id: int, ciphertext: str) -> list[float]:
    raw = base64.b64decode(ciphertext)
    try:
        data = AESGCM(_key()).decrypt(raw[:12], raw[12:], str(student_id).encode())
    except InvalidTag:
        raise HTTPException(500, "Stored face template could not be read")
    return list(struct.unpack(f"<{DIMENSIONS}f", data))


def valid_template(values: list[float]) -> list[float]:
    if any(not math.isfinite(v) or abs(v) > 2 for v in values):
        raise HTTPException(422, "Invalid face template")
    norm = math.sqrt(sum(v * v for v in values))
    if not 0.9 <= norm <= 1.1:
        raise HTTPException(422, "Face template must be normalized")
    return values


def require_face(db: Session, user: User, face_verified: bool) -> None:
    """Called before QR check-in. The claim is made by the phone: it is evidence, not proof."""
    current = mode()
    if current == "off":
        return
    enrolled = db.get(FaceTemplate, user.id) is not None
    if not enrolled:
        if current == "required":
            raise HTTPException(409, "Face enrollment is required. Ask your teacher to confirm attendance.")
        return
    if not face_verified:
        raise HTTPException(409, "Face verification is required before check-in")


def face_enrolled(db: Session, user: User) -> bool:
    return mode() != "off" and db.get(FaceTemplate, user.id) is not None


def staff(user: User = Depends(current_user)) -> User:
    if user.role not in ("teacher", "admin"):
        raise HTTPException(403, "Teacher or administrator role required")
    return user


def student_for(db: Session, staff_user: User, student_id: int) -> User:
    student = db.get(User, student_id)
    if student is None or student.role != "student":
        raise HTTPException(404, "Student not found")
    if staff_user.role == "teacher":
        taught = db.scalar(select(Enrollment.student_id).join(CourseOffering, CourseOffering.id == Enrollment.course_id)
                           .where(CourseOffering.teacher_id == staff_user.id, Enrollment.student_id == student_id).limit(1))
        if taught is None:
            raise HTTPException(404, "Student not found")
    return student


@router.get("/face/templates/me")
def my_template(user: User = Depends(current_user), db: Session = Depends(get_db)):
    student_only(user)
    current = mode()
    row = db.get(FaceTemplate, user.id) if current != "off" else None
    # A template is decrypted and returned only for explicitly enabled local
    # matching. The default production posture exposes enrollment status only;
    # deployments can replace this flow with hardware-backed/server-side matching.
    template = (unseal(user.id, row.ciphertext)
                if row and settings().face_template_client_download else None)
    return {"mode": current, "enrolled": row is not None, "model": row.model if row else None,
            "template": template}


@router.get("/face/roster")
def roster(user: User = Depends(staff), db: Session = Depends(get_db)):
    query = select(User).where(User.role == "student")
    if user.role == "teacher":
        query = query.where(User.id.in_(select(Enrollment.student_id).join(CourseOffering, CourseOffering.id == Enrollment.course_id)
                                         .where(CourseOffering.teacher_id == user.id)))
    enrolled = set(db.scalars(select(FaceTemplate.student_id)))
    return [{"id": s.id, "name": s.name, "email": s.email, "enrolled": s.id in enrolled}
            for s in db.scalars(query.order_by(User.name))]


@router.put("/face/templates/{student_id}", status_code=204)
def enroll(student_id: int, body: TemplateInput, user: User = Depends(staff), db: Session = Depends(get_db)):
    student_for(db, user, student_id)
    now = now_utc()
    sealed = seal(student_id, valid_template(body.template))
    row = db.get(FaceTemplate, student_id)
    if row is None:
        db.add(FaceTemplate(student_id=student_id, ciphertext=sealed, model=body.model, enrolled_by=user.id,
                            enrolled_at=now, consent_recorded_at=now))
    else:
        row.ciphertext, row.model, row.enrolled_by, row.enrolled_at, row.consent_recorded_at = sealed, body.model, user.id, now, now
    db.add(AdminAudit(actor_id=user.id, action="face_enrolled", target_type="student", target_id=str(student_id),
                      reason="Staff recorded a face template with the student's consent", recorded_at=now))
    db.commit()
    return Response(status_code=204)


@router.delete("/face/templates/{student_id}", status_code=204)
def delete_template(student_id: int, user: User = Depends(staff), db: Session = Depends(get_db)):
    student_for(db, user, student_id)
    row = db.get(FaceTemplate, student_id)
    if row is not None:
        db.delete(row)
        db.add(AdminAudit(actor_id=user.id, action="face_deleted", target_type="student", target_id=str(student_id),
                          reason="Staff deleted a face template", recorded_at=now_utc()))
        db.commit()
    return Response(status_code=204)
