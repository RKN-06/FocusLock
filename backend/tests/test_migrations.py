import os
import subprocess
import sys
from pathlib import Path
from sqlalchemy import create_engine, inspect, text


def test_migration_round_trip(tmp_path):
    database = tmp_path / "migrations.db"
    env = {**os.environ, "DATABASE_URL": f"sqlite:///{database.as_posix()}"}
    cwd = Path(__file__).resolve().parents[1]
    def alembic(*args):
        subprocess.run([sys.executable, "-m", "alembic", *args], cwd=cwd, env=env, check=True, capture_output=True, text=True)
    alembic("upgrade", "0001")
    old_engine = create_engine(env["DATABASE_URL"])
    with old_engine.begin() as connection:
        connection.execute(text("INSERT INTO users (id,email,name,role,is_demo) VALUES (1,'teacher@test.invalid','Teacher','teacher',1),(3,'student@test.invalid','Student','student',1)"))
        connection.execute(text("INSERT INTO course_offerings (id,code,title,term,teacher_id) VALUES (1,'C1','Class','Term',1)"))
        connection.execute(text("INSERT INTO enrollments (course_id,student_id) VALUES (1,3)"))
        connection.execute(text("INSERT INTO sessions (id,course_id,title,starts_at,ends_at) VALUES (1,1,'Old class','2026-10-10 10:00:00','2026-10-10 11:00:00')"))
        connection.execute(text("INSERT INTO attendance (session_id,student_id,course_id,status,method,confirmed_by,updated_at) VALUES (1,3,1,'present','teacher_confirmed',1,'2026-10-10 10:15:00')"))
    old_engine.dispose()
    alembic("upgrade", "head")
    engine = create_engine(env["DATABASE_URL"])
    assert set(inspect(engine).get_table_names()) == {"alembic_version", "users", "course_offerings", "enrollments", "sessions", "attendance", "dev_tokens", "devices", "device_challenges", "attendance_events", "pending_attendance", "course_resources", "focus_events", "ble_receivers", "ble_credentials", "ble_sightings", "optional_consents", "data_requests", "admin_audit", "rate_attempts", "integrity_receipts", "focus_exit_requests", "face_templates", "time_slots", "student_groups", "group_members", "course_groups", "session_codes"}
    with engine.connect() as connection:
        assert connection.execute(text("SELECT version_num FROM alembic_version")).scalar_one() == "0012"
        assert connection.execute(text("SELECT state FROM sessions WHERE id=1")).scalar_one() == "ready"
        assert connection.execute(text("SELECT status FROM attendance WHERE session_id=1 AND student_id=3")).scalar_one() == "present"
        assert connection.execute(text("SELECT action FROM attendance_events WHERE session_id=1 AND student_id=3")).scalar_one() == "legacy_import"
    engine.dispose()
    alembic("check")
    alembic("downgrade", "base")
    alembic("upgrade", "head")
