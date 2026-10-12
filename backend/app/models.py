from datetime import datetime, time
from sqlalchemy import false, true, Boolean, Time, CheckConstraint, DateTime, ForeignKey, ForeignKeyConstraint, Integer, String, UniqueConstraint, Text
from sqlalchemy.orm import Mapped, mapped_column
from .db import Base


class User(Base):
    __tablename__ = "users"
    __table_args__ = (CheckConstraint("role IN ('student', 'teacher', 'admin')", name="user_role"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    name: Mapped[str] = mapped_column(String(100))
    role: Mapped[str] = mapped_column(String(16))
    is_demo: Mapped[bool] = mapped_column(Boolean, default=False)
    oidc_subject: Mapped[str | None] = mapped_column(String(255), unique=True, nullable=True)


class CourseOffering(Base):
    __tablename__ = "course_offerings"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(32))
    title: Mapped[str] = mapped_column(String(150))
    term: Mapped[str] = mapped_column(String(40))
    teacher_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)


class Enrollment(Base):
    __tablename__ = "enrollments"
    course_id: Mapped[int] = mapped_column(ForeignKey("course_offerings.id"), primary_key=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    # "group" rows follow group membership automatically; "manual" rows are never removed by group changes.
    source: Mapped[str] = mapped_column(String(8), default="manual", server_default="manual")


class ClassSession(Base):
    __tablename__ = "sessions"
    __table_args__ = (
        UniqueConstraint("id", "course_id", name="session_course_unique"),
        CheckConstraint("ends_at > starts_at", name="session_time_order"),
        CheckConstraint("state IN ('ready', 'checkin_pending', 'active', 'paused', 'ended')", name="session_state"),
    )
    id: Mapped[int] = mapped_column(primary_key=True)
    course_id: Mapped[int] = mapped_column(ForeignKey("course_offerings.id"), index=True)
    title: Mapped[str] = mapped_column(String(150))
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    state: Mapped[str] = mapped_column(String(16), default="ready")
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    room_code: Mapped[str] = mapped_column(String(40), default="UNASSIGNED")


class Attendance(Base):
    __tablename__ = "attendance"
    __table_args__ = (
        ForeignKeyConstraint(["session_id", "course_id"], ["sessions.id", "sessions.course_id"]),
        ForeignKeyConstraint(["course_id", "student_id"], ["enrollments.course_id", "enrollments.student_id"]),
        CheckConstraint("status IN ('present', 'absent', 'excused')", name="attendance_status"),
        CheckConstraint("method IN ('teacher_confirmed', 'qr', 'code')", name="attendance_method"),
    )
    session_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    student_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    course_id: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(16))
    method: Mapped[str] = mapped_column(String(32), default="teacher_confirmed")
    face_verified: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    confirmed_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class Device(Base):
    __tablename__ = "devices"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    public_key: Mapped[str] = mapped_column(Text)
    fingerprint: Mapped[str] = mapped_column(String(64), unique=True)
    registered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class DeviceChallenge(Base):
    __tablename__ = "device_challenges"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    purpose: Mapped[str] = mapped_column(String(16))
    nonce: Mapped[str] = mapped_column(String(64))
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id"), nullable=True)
    session_id: Mapped[int | None] = mapped_column(ForeignKey("sessions.id"), nullable=True)
    qr_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    request_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)


class AttendanceEvent(Base):
    __tablename__ = "attendance_events"
    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("sessions.id"), index=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    actor_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(32))
    previous_status: Mapped[str | None] = mapped_column(String(16), nullable=True)
    new_status: Mapped[str] = mapped_column(String(16))
    reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class PendingAttendance(Base):
    __tablename__ = "pending_attendance"
    __table_args__ = (UniqueConstraint("session_id", "student_id", name="pending_session_student_unique"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("sessions.id"), index=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    reason: Mapped[str] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    resolved_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)


class DevToken(Base):
    """Hashed, expiring demo credentials; never accepted outside development."""
    __tablename__ = "dev_tokens"
    digest: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class CourseResource(Base):
    __tablename__ = "course_resources"
    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("sessions.id"), index=True)
    title: Mapped[str] = mapped_column(String(150))
    url: Mapped[str] = mapped_column(String(2048))
    kind: Mapped[str] = mapped_column(String(8))
    downloadable: Mapped[bool] = mapped_column(Boolean, default=False)
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class FocusEvent(Base):
    __tablename__ = "focus_events"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("sessions.id"), index=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    action: Mapped[str] = mapped_column(String(16))
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class FaceTemplate(Base):
    """Encrypted 128-number face template recorded by staff; never a photo. Matching happens on the student's phone."""
    __tablename__ = "face_templates"
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    ciphertext: Mapped[str] = mapped_column(String(1024))
    model: Mapped[str] = mapped_column(String(32))
    enrolled_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    enrolled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    consent_recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class FocusExitRequest(Base):
    __tablename__ = "focus_exit_requests"
    __table_args__ = (CheckConstraint("status IN ('pending', 'approved', 'denied')", name="focus_exit_status"),
                      CheckConstraint("kind IN ('exit', 'resume')", name="focus_exit_kind"))
    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("sessions.id"), index=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(8), default="exit", server_default="exit")
    status: Mapped[str] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    resolved_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class BleReceiver(Base):
    __tablename__ = "ble_receivers"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    course_id: Mapped[int] = mapped_column(ForeignKey("course_offerings.id"), index=True)
    room_code: Mapped[str] = mapped_column(String(40))
    token_digest: Mapped[str] = mapped_column(String(64), unique=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    last_heartbeat_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class BleCredential(Base):
    __tablename__ = "ble_credentials"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("sessions.id"), index=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class BleSighting(Base):
    __tablename__ = "ble_sightings"
    __table_args__ = (UniqueConstraint("receiver_id", "credential_id", "slot", name="ble_sighting_unique"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    receiver_id: Mapped[str] = mapped_column(ForeignKey("ble_receivers.id"))
    credential_id: Mapped[str] = mapped_column(ForeignKey("ble_credentials.id"), index=True)
    slot: Mapped[int] = mapped_column(Integer)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class OptionalConsent(Base):
    __tablename__ = "optional_consents"
    __table_args__ = (CheckConstraint("feature IN ('vpn', 'bluetooth')", name="optional_consent_feature"),)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    feature: Mapped[str] = mapped_column(String(16), primary_key=True)
    disclosure_version: Mapped[str] = mapped_column(String(32))
    granted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    withdrawn_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class DataRequest(Base):
    __tablename__ = "data_requests"
    __table_args__ = (CheckConstraint("kind IN ('correction', 'deletion')", name="data_request_kind"),
                      CheckConstraint("status IN ('pending', 'accepted', 'declined')", name="data_request_status"))
    id: Mapped[int] = mapped_column(primary_key=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    session_id: Mapped[int | None] = mapped_column(ForeignKey("sessions.id"), nullable=True)
    kind: Mapped[str] = mapped_column(String(16))
    reason: Mapped[str] = mapped_column(String(500))
    status: Mapped[str] = mapped_column(String(16), default="pending")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    reviewed_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    resolution: Mapped[str | None] = mapped_column(String(500), nullable=True)


class AdminAudit(Base):
    __tablename__ = "admin_audit"
    id: Mapped[int] = mapped_column(primary_key=True)
    actor_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    action: Mapped[str] = mapped_column(String(48))
    target_type: Mapped[str] = mapped_column(String(32))
    target_id: Mapped[str] = mapped_column(String(64))
    reason: Mapped[str] = mapped_column(String(500))
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class RateAttempt(Base):
    __tablename__ = "rate_attempts"
    id: Mapped[int] = mapped_column(primary_key=True)
    key_digest: Mapped[str] = mapped_column(String(64), index=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)


class IntegrityReceipt(Base):
    __tablename__ = "integrity_receipts"
    token_digest: Mapped[str] = mapped_column(String(64), primary_key=True)
    request_hash: Mapped[str] = mapped_column(String(64))
    verified_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))

class TimeSlot(Base):
    """A predefined class period. Only administrators change these; teachers pick from them when scheduling."""
    __tablename__ = "time_slots"
    __table_args__ = (CheckConstraint("ends_at > starts_at", name="slot_time_order"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    label: Mapped[str] = mapped_column(String(60), unique=True)
    starts_at: Mapped[time] = mapped_column(Time)
    ends_at: Mapped[time] = mapped_column(Time)
    active: Mapped[bool] = mapped_column(Boolean, default=True, server_default=true())
    updated_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class StudentGroup(Base):
    """A cohort such as "CSE - Year 3 - A". Courses are linked to groups, and members are enrolled automatically."""
    __tablename__ = "student_groups"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(80), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class GroupMember(Base):
    __tablename__ = "group_members"
    group_id: Mapped[int] = mapped_column(ForeignKey("student_groups.id"), primary_key=True)
    student_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)


class CourseGroup(Base):
    __tablename__ = "course_groups"
    course_id: Mapped[int] = mapped_column(ForeignKey("course_offerings.id"), primary_key=True)
    group_id: Mapped[int] = mapped_column(ForeignKey("student_groups.id"), primary_key=True)


class SessionCode(Base):
    """Backup codes a teacher sets for a session: one to enter (check in) and one to exit focus. Stored only as keyed digests."""
    __tablename__ = "session_codes"
    session_id: Mapped[int] = mapped_column(ForeignKey("sessions.id"), primary_key=True)
    enter_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)
    exit_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)
    updated_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
