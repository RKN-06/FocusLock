import secrets
from datetime import datetime, timedelta, timezone
from typing import Literal
from fastapi import Depends, FastAPI, HTTPException, Response, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, select, text
from sqlalchemy.orm import Session
from .auth import bearer, current_user, digest, teacher, utc
from .config import settings
from .db import get_db
from .models import AdminAudit, Attendance, AttendanceEvent, ClassSession, CourseOffering, DevToken, Enrollment, PendingAttendance, User

app = FastAPI(title="FocusLock", version="0.6.0")
app.add_middleware(CORSMiddleware, allow_origins=settings().cors_origins.split(","), allow_methods=["GET", "POST", "PUT", "DELETE"], allow_headers=["Authorization", "Content-Type"])


@app.middleware("http")
async def no_store(request, call_next):
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    return response


class Login(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(max_length=254)
    password: str = Field(max_length=256)


class Confirmation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: Literal["present", "absent", "excused"]
    reason: str | None = Field(default=None, max_length=500)


class NewSession(BaseModel):
    model_config = ConfigDict(extra="forbid")
    course_id: int = Field(gt=0)
    title: str = Field(min_length=3, max_length=150)
    starts_at: datetime
    ends_at: datetime
    room_code: str = Field(default="UNASSIGNED", min_length=1, max_length=40, pattern=r"^[A-Za-z0-9._-]+$")


def user_view(user):
    return {"id": user.id, "name": user.name, "email": user.email, "role": user.role}


def course_view(course):
    return {"id": course.id, "code": course.code, "title": course.title, "term": course.term}


def attendance_view(row):
    return {"session_id": row.session_id, "student_id": row.student_id, "status": row.status, "method": row.method, "confirmed_by": row.confirmed_by, "updated_at": utc(row.updated_at)}


def visible_course(db, user, course_id):
    course = db.get(CourseOffering, course_id)
    allowed = course is not None and (course.teacher_id == user.id if user.role == "teacher" else db.get(Enrollment, (course_id, user.id)) is not None)
    if not allowed:
        raise HTTPException(404, "Course not found")
    return course


def visible_session(db, user, session_id):
    session = db.get(ClassSession, session_id)
    if session is None:
        raise HTTPException(404, "Session not found")
    visible_course(db, user, session.course_id)
    return session


def session_view(db, user, session):
    course = db.get(CourseOffering, session.course_id)
    own = db.get(Attendance, (session.id, user.id)) if user.role == "student" else None
    pending = db.scalar(select(PendingAttendance).where(PendingAttendance.session_id == session.id, PendingAttendance.student_id == user.id, PendingAttendance.resolved_at.is_(None))) if user.role == "student" else None
    return {"id": session.id, "course_id": session.course_id, "course_title": course.title, "title": session.title, "room_code": session.room_code, "starts_at": utc(session.starts_at), "ends_at": utc(session.ends_at), "state": session.state, "attendance": attendance_view(own) if own else None, "pending_review": pending is not None}


@app.get("/health/live")
def live():
    return {"status": "ok"}


@app.get("/health/ready")
def ready(db: Session = Depends(get_db)):
    try:
        revision = db.execute(text("SELECT version_num FROM alembic_version")).scalar_one()
        if revision != "0013":
            raise ValueError("Schema revision mismatch")
        cfg = settings()
        if cfg.app_env == "production" and (not cfg.oidc_clients_configured or
                min(len(cfg.qr_signing_key), len(cfg.policy_signing_key)) < 32 or
                cfg.play_integrity_mode not in ("off", "optional", "required") or
                cfg.face_check_mode not in ("off", "enrolled", "required") or
                (cfg.face_check_mode != "off" and len(cfg.face_template_key) < 32)):
            raise ValueError("Production identity or signing configuration missing")
    except Exception:
        raise HTTPException(503, "Database or schema unavailable")
    return {"status": "ready"}


@app.get("/internal/retention")
def retention_job(request: Request, db: Session = Depends(get_db)):
    from .retention import purge
    secret = settings().cron_secret
    header = request.headers.get("authorization", "")
    if len(secret) < 16 or not secrets.compare_digest(header, f"Bearer {secret}"):
        raise HTTPException(401, "Unauthorized")
    return {"deleted": purge(db)}


@app.get("/auth/config")
def auth_config():
    return {"development_login": settings().dev_auth_enabled,
            "college_oidc": settings().oidc_clients_configured and not settings().dev_auth_enabled,
            "issuer": settings().oidc_issuer if settings().oidc_configured else None,
            "audience": settings().oidc_audience if settings().oidc_configured else None,
            "authorization_endpoint": settings().oidc_authorization_endpoint if settings().oidc_configured else None,
            "dashboard_client_id": settings().oidc_dashboard_client_id if settings().oidc_configured else None,
            "dashboard_redirect_uri": settings().oidc_dashboard_redirect_uri if settings().oidc_configured else None,
            "android_client_id": settings().oidc_android_client_id if settings().oidc_configured else None,
            "android_redirect_uri": settings().oidc_android_redirect_uri if settings().oidc_configured else None}


@app.post("/auth/dev-login")
def dev_login(body: Login, db: Session = Depends(get_db)):
    if not settings().dev_auth_enabled:
        raise HTTPException(404, "Not found")
    from .stage6 import rate_limit
    rate_limit(db, "dev-login", body.email.strip().lower(), 10, 300)
    db.commit()
    valid_password = secrets.compare_digest(body.password.encode(), settings().dev_password.encode())
    user = db.scalar(select(User).where(User.email == body.email.strip().lower(), User.is_demo.is_(True)))
    if not valid_password or user is None:
        raise HTTPException(401, "Invalid development credentials")
    now = datetime.now(timezone.utc)
    db.execute(delete(DevToken).where(DevToken.expires_at <= now))
    token = secrets.token_urlsafe(32)
    db.add(DevToken(digest=digest(token), user_id=user.id, expires_at=now + timedelta(hours=8)))
    db.commit()
    return {"access_token": token, "token_type": "bearer", "expires_in": 28800, "user": user_view(user)}


@app.post("/auth/logout", status_code=204)
def logout(user: User = Depends(current_user), credentials=Depends(bearer), db: Session = Depends(get_db)):
    if settings().dev_auth_enabled:
        db.execute(delete(DevToken).where(DevToken.digest == digest(credentials.credentials)))
        db.commit()
    return Response(status_code=204)


@app.get("/me")
def me(user: User = Depends(current_user)):
    return user_view(user)


@app.get("/courses")
def courses(user: User = Depends(current_user), db: Session = Depends(get_db)):
    query = select(CourseOffering)
    if user.role == "teacher":
        query = query.where(CourseOffering.teacher_id == user.id)
    else:
        query = query.join(Enrollment).where(Enrollment.student_id == user.id)
    rows = db.scalars(query.order_by(CourseOffering.id)).all()
    if user.role != "teacher":
        return [course_view(c) for c in rows]
    from .groups import course_detail
    return [course_detail(db, c) for c in rows]


@app.get("/courses/{course_id}/roster")
def roster(course_id: int, user: User = Depends(teacher), db: Session = Depends(get_db)):
    visible_course(db, user, course_id)
    return [{**user_view(u), "kind": kind} for u, kind in db.execute(select(User, Enrollment.kind).join(Enrollment, Enrollment.student_id == User.id).where(Enrollment.course_id == course_id).order_by(User.name)).all()]


@app.get("/sessions")
def sessions(course_id: int | None = None, user: User = Depends(current_user), db: Session = Depends(get_db)):
    query = select(ClassSession).join(CourseOffering)
    if user.role == "teacher":
        query = query.where(CourseOffering.teacher_id == user.id)
    else:
        query = query.join(Enrollment, Enrollment.course_id == CourseOffering.id).where(Enrollment.student_id == user.id)
    if course_id is not None:
        visible_course(db, user, course_id)
        query = query.where(ClassSession.course_id == course_id)
    return [session_view(db, user, s) for s in db.scalars(query.order_by(ClassSession.starts_at))]


@app.post("/sessions", status_code=201)
def create_session(body: NewSession, user: User = Depends(teacher), db: Session = Depends(get_db)):
    course = visible_course(db, user, body.course_id)
    now = datetime.now(timezone.utc)
    start, end = utc(body.starts_at), utc(body.ends_at)
    if body.starts_at.tzinfo is None or body.ends_at.tzinfo is None:
        raise HTTPException(422, "Session times must include a timezone")
    if start < now - timedelta(minutes=5) or start > now + timedelta(days=365):
        raise HTTPException(422, "Session start must be between now and one year ahead")
    if end <= start or end - start > timedelta(hours=8):
        raise HTTPException(422, "Session duration must be greater than zero and no more than eight hours")
    from .stage6 import rate_limit
    rate_limit(db, "session-create", str(user.id), 20, 3600)
    row = ClassSession(course_id=course.id, title=body.title.strip(), starts_at=start,
                       ends_at=end, state="ready", room_code=body.room_code)
    if len(row.title) < 3:
        raise HTTPException(422, "Session title must contain at least three characters")
    db.add(row)
    db.flush()
    db.add(AdminAudit(actor_id=user.id, action="session_created", target_type="session",
                      target_id=str(row.id), reason="Teacher scheduled class", recorded_at=now))
    db.commit()
    return session_view(db, user, row)


@app.get("/sessions/{session_id}")
def session_details(session_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)):
    return session_view(db, user, visible_session(db, user, session_id))


@app.get("/attendance/me")
def history(user: User = Depends(current_user), db: Session = Depends(get_db)):
    if user.role != "student":
        raise HTTPException(403, "Student role required")
    rows = db.scalars(select(Attendance).where(Attendance.student_id == user.id).order_by(Attendance.updated_at.desc()))
    return [{**attendance_view(a), "session": session_view(db, user, db.get(ClassSession, a.session_id))} for a in rows]


@app.get("/sessions/{session_id}/attendance")
def session_attendance(session_id: int, user: User = Depends(teacher), db: Session = Depends(get_db)):
    visible_session(db, user, session_id)
    return [attendance_view(a) for a in db.scalars(select(Attendance).where(Attendance.session_id == session_id).order_by(Attendance.student_id))]


@app.put("/sessions/{session_id}/attendance/{student_id}")
def confirm(session_id: int, student_id: int, body: Confirmation, user: User = Depends(teacher), db: Session = Depends(get_db)):
    session = visible_session(db, user, session_id)
    if db.get(Enrollment, (session.course_id, student_id)) is None:
        raise HTTPException(404, "Enrolled student not found")
    if utc(session.starts_at) > datetime.now(timezone.utc):
        raise HTTPException(409, "Attendance can be confirmed once the session starts")
    # Serialize confirmations per session, including creation of a previously absent row.
    db.execute(select(ClassSession).where(ClassSession.id == session_id).with_for_update()).scalar_one()
    row = db.get(Attendance, (session_id, student_id))
    previous = row.status if row else None
    pending = db.scalar(select(PendingAttendance).where(PendingAttendance.session_id == session_id, PendingAttendance.student_id == student_id, PendingAttendance.resolved_at.is_(None)))
    if previous == body.status and row.method == "teacher_confirmed":
        return attendance_view(row)
    if (previous is not None or pending is not None) and (body.reason is None or len(body.reason.strip()) < 10):
        raise HTTPException(422, "A correction or pending-review reason of at least 10 characters is required")
    if row is None:
        row = Attendance(session_id=session_id, student_id=student_id, course_id=session.course_id)
        db.add(row)
    row.status = body.status
    row.method = "teacher_confirmed"
    row.confirmed_by = user.id
    row.updated_at = datetime.now(timezone.utc)
    db.add(AttendanceEvent(session_id=session_id, student_id=student_id, actor_id=user.id, action="correction" if previous is not None else "teacher_confirmed", previous_status=previous, new_status=body.status, reason=body.reason.strip() if body.reason else None, recorded_at=row.updated_at))
    if pending:
        pending.resolved_at = row.updated_at
        pending.resolved_by = user.id
    db.commit()
    return attendance_view(row)


from .stage2 import router as stage2_router  # noqa: E402
app.include_router(stage2_router)
from .stage3 import router as stage3_router  # noqa: E402
app.include_router(stage3_router)
from .stage5 import router as stage5_router  # noqa: E402
app.include_router(stage5_router)
from .stage6 import router as stage6_router  # noqa: E402
app.include_router(stage6_router)
from .face import router as face_router  # noqa: E402
app.include_router(face_router)
from .session_admin import router as session_admin_router  # noqa: E402
app.include_router(session_admin_router)
from .slots import router as slots_router  # noqa: E402
app.include_router(slots_router)
from .course_admin import router as course_admin_router  # noqa: E402
app.include_router(course_admin_router)
from .groups import router as groups_router  # noqa: E402
app.include_router(groups_router)
from .timetable import router as timetable_router  # noqa: E402
app.include_router(timetable_router)
from .analytics import router as analytics_router  # noqa: E402
app.include_router(analytics_router)
from .access_codes import router as access_codes_router  # noqa: E402
app.include_router(access_codes_router)
from .reports import router as reports_router
app.include_router(reports_router)
