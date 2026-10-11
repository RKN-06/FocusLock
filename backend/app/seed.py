"""Explicit demo fixtures, never invoked by application import or production startup."""
from datetime import datetime, timedelta, timezone
from sqlalchemy import select, text
from .config import settings
from .db import SessionLocal
from .models import Attendance, AttendanceEvent, BleReceiver, ClassSession, CourseOffering, Enrollment, User
from .auth import digest


def seed(db):
    if not settings().dev_auth_enabled:
        raise RuntimeError("Seeding requires APP_ENV=development, ENABLE_DEV_AUTH=true and DEV_PASSWORD")
    # Do not modify an existing or partially populated database.
    if db.scalar(select(User.id).limit(1)) is not None:
        return False
    db.add_all([
        User(id=1, email="teacher@focuslock.test", name="Alex Morgan", role="teacher", is_demo=True),
        User(id=2, email="teacher2@focuslock.test", name="Sam Lee", role="teacher", is_demo=True),
        User(id=3, email="student@focuslock.test", name="Jordan Patel", role="student", is_demo=True),
        User(id=4, email="student2@focuslock.test", name="Taylor Chen", role="student", is_demo=True),
        User(id=5, email="student3@focuslock.test", name="Casey Kim", role="student", is_demo=True),
        User(id=6, email="admin@focuslock.test", name="Demo Administrator", role="admin", is_demo=True),
    ])
    db.flush()
    db.add_all([
        CourseOffering(id=1, code="CS101", title="Introduction to Computing", term="Demo term", teacher_id=1),
        CourseOffering(id=2, code="DS102", title="Data and Society", term="Demo term", teacher_id=2),
    ])
    db.flush()
    db.add_all([Enrollment(course_id=1, student_id=3), Enrollment(course_id=1, student_id=4), Enrollment(course_id=2, student_id=5)])
    now = datetime.now(timezone.utc)
    db.add_all([
        ClassSession(id=1, course_id=1, title="Getting started", starts_at=now - timedelta(minutes=10), ends_at=now + timedelta(minutes=50), state="ready", room_code="A101"),
        ClassSession(id=2, course_id=2, title="Data in everyday life", starts_at=now - timedelta(minutes=10), ends_at=now + timedelta(minutes=50), state="ready", room_code="B201"),
        ClassSession(id=3, course_id=1, title="Welcome and orientation", starts_at=now - timedelta(days=1), ends_at=now - timedelta(days=1) + timedelta(hours=1), state="ended", started_at=now - timedelta(days=1), ended_at=now - timedelta(days=1) + timedelta(hours=1), room_code="A101"),
    ])
    db.flush()
    db.add(Attendance(session_id=3, student_id=3, course_id=1, status="present", method="teacher_confirmed", confirmed_by=1, updated_at=now - timedelta(days=1)))
    db.add(AttendanceEvent(session_id=3, student_id=3, actor_id=1, action="teacher_confirmed", previous_status=None, new_status="present", reason="Demo historical teacher confirmation", recorded_at=now - timedelta(days=1)))
    if settings().dev_receiver_token:
        db.add(BleReceiver(id="11111111-1111-4111-8111-111111111111", course_id=1, room_code="A101", token_digest=digest(settings().dev_receiver_token), active=True))
    db.commit()
    if db.bind.dialect.name == "postgresql":
        # The demo rows use fixed ids; move each id counter past them so new rows do not collide.
        for table in ("users", "course_offerings", "sessions"):
            db.execute(text(f"SELECT setval(pg_get_serial_sequence('{table}', 'id'), (SELECT MAX(id) FROM {table}))"))
        db.commit()
    return True


if __name__ == "__main__":
    if settings().dev_auth_enabled:
        with SessionLocal() as db:
            print("Demo records created." if seed(db) else "Database already populated; demo seed skipped.")
    else:
        print("Demo seed disabled. No records created.")
