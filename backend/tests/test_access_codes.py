from datetime import datetime, timedelta, timezone
from sqlalchemy import select
from app.models import Attendance, AttendanceEvent, ClassSession
from conftest import login


def window_session(client, state="ready", starts=-5, ends=55):
    """Session 1 is already inside its window in the demo data; this shifts it so the tests control the clock."""
    with client.db_factory() as db:
        row = db.get(ClassSession, 1)
        now = datetime.now(timezone.utc)
        row.starts_at, row.ends_at, row.state = now + timedelta(minutes=starts), now + timedelta(minutes=ends), state
        if state != "ready":
            row.started_at = row.starts_at
        db.commit()


def set_codes(client, teacher, enter="maple-42", exit_="river-77"):
    return client.put("/sessions/1/access-codes", headers=teacher, json={"enter_code": enter, "exit_code": exit_})


def test_teacher_sets_codes_and_only_status_is_ever_returned(client):
    teacher = login(client, "teacher@focuslock.test")
    assert client.get("/sessions/1/access-codes", headers=teacher).json() == {"enter_set": False, "exit_set": False, "updated_at": None}
    result = set_codes(client, teacher)
    assert result.status_code == 200 and result.json()["enter_set"] and result.json()["exit_set"]
    assert "maple" not in result.text and "river" not in client.get("/sessions/1/access-codes", headers=teacher).text
    with client.db_factory() as db:
        from app.models import SessionCode
        row = db.get(SessionCode, 1)
        assert row.enter_digest and "maple" not in row.enter_digest and row.enter_digest != row.exit_digest
    assert client.delete("/sessions/1/access-codes", headers=teacher).status_code == 204
    assert client.get("/sessions/1/access-codes", headers=teacher).json()["enter_set"] is False


def test_code_rules_and_permissions(client):
    teacher = login(client, "teacher@focuslock.test")
    assert client.put("/sessions/1/access-codes", headers=teacher, json={}).status_code == 422
    assert client.put("/sessions/1/access-codes", headers=teacher, json={"enter_code": "short"}).status_code == 422
    assert client.put("/sessions/1/access-codes", headers=teacher, json={"enter_code": "aaaaaaaa"}).status_code == 422
    assert client.put("/sessions/1/access-codes", headers=teacher, json={"enter_code": "same-code-9", "exit_code": "SAME-code-9"}).status_code == 422
    assert client.put("/sessions/1/access-codes", headers=teacher, json={"enter_code": "maple-42", "extra": 1}).status_code == 422
    assert set_codes(client, login(client, "teacher2@focuslock.test")).status_code == 404
    assert set_codes(client, login(client)).status_code == 403
    assert client.get("/sessions/1/access-codes", headers=login(client)).status_code == 403
    assert client.put("/sessions/3/access-codes", headers=teacher, json={"enter_code": "maple-42"}).status_code == 409  # ended


def test_student_checks_in_with_the_enter_code_inside_the_class_window(client):
    teacher = login(client, "teacher@focuslock.test")
    student = login(client)
    window_session(client)
    set_codes(client, teacher)
    result = client.post("/sessions/1/check-in/code", headers=student, json={"code": " MAPLE-42 "})
    assert result.status_code == 200, result.text
    assert result.json() == {"status": "present", "method": "code", "duplicate": False}
    with client.db_factory() as db:
        row = db.get(Attendance, (1, 3))
        assert row.method == "code" and row.face_verified is False and row.confirmed_by is None
        event = db.scalar(select(AttendanceEvent).where(AttendanceEvent.session_id == 1, AttendanceEvent.action == "code_checkin"))
        assert event is not None and "lower assurance" in event.reason
    again = client.post("/sessions/1/check-in/code", headers=student, json={"code": "maple-42"}).json()
    assert again["duplicate"] is True and again["method"] == "code"
    table = client.get("/sessions/1/attendance", headers=teacher).json()
    assert [row["method"] for row in table if row["student_id"] == 3] == ["code"]


def test_wrong_codes_are_refused_counted_and_eventually_locked_out(client):
    teacher = login(client, "teacher@focuslock.test")
    student = login(client)
    window_session(client)
    set_codes(client, teacher)
    statuses = [client.post("/sessions/1/check-in/code", headers=student, json={"code": f"guess-{n}"}).status_code for n in range(11)]
    assert statuses[:10] == [403] * 10 and statuses[10] == 429
    assert client.post("/sessions/1/check-in/code", headers=student, json={"code": "maple-42"}).status_code == 429  # even the right code waits
    with client.db_factory() as db:
        assert db.get(Attendance, (1, 3)) is None


def test_codes_do_not_work_outside_the_window_without_a_code_or_for_other_courses(client):
    teacher = login(client, "teacher@focuslock.test")
    student = login(client)
    set_codes(client, teacher)
    window_session(client, starts=120, ends=180)
    assert client.post("/sessions/1/check-in/code", headers=student, json={"code": "maple-42"}).status_code == 409
    window_session(client, starts=-90, ends=-30)
    assert client.post("/sessions/1/check-in/code", headers=student, json={"code": "maple-42"}).status_code == 409
    window_session(client, state="ended", starts=-30, ends=30)
    assert client.post("/sessions/1/check-in/code", headers=student, json={"code": "maple-42"}).status_code == 409
    window_session(client)
    client.delete("/sessions/1/access-codes", headers=teacher)
    assert client.post("/sessions/1/check-in/code", headers=student, json={"code": "maple-42"}).status_code == 403
    set_codes(client, teacher)
    outsider = login(client, "student3@focuslock.test")  # not enrolled in course 1
    assert client.post("/sessions/1/check-in/code", headers=outsider, json={"code": "maple-42"}).status_code in (403, 404)
    assert client.post("/sessions/1/check-in/code", headers=teacher, json={"code": "maple-42"}).status_code == 403


def test_exit_code_approves_leaving_focus_and_enter_code_is_not_the_exit_code(client):
    teacher = login(client, "teacher@focuslock.test")
    student = login(client)
    window_session(client, state="active")
    set_codes(client, teacher)
    assert client.post("/sessions/1/focus-exit/code", headers=student, json={"code": "maple-42"}).status_code == 403
    assert client.post("/sessions/1/focus-exit/code", headers=student, json={"code": "river-77"}).json() == {"approved": True}
    assert client.post("/sessions/1/check-in/code", headers=student, json={"code": "river-77"}).status_code == 403


def test_analytics_counts_code_check_ins_separately(client):
    teacher = login(client, "teacher@focuslock.test")
    student = login(client)
    window_session(client, state="active")
    set_codes(client, teacher)
    client.post("/sessions/1/check-in/code", headers=student, json={"code": "maple-42"})
    body = client.get("/courses/1/analytics", headers=teacher).json()
    assert body["summary"]["via_code"] == 1
    assert next(row for row in body["sessions"] if row["id"] == 1)["via_code"] == 1
