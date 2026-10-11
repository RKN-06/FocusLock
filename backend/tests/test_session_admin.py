from sqlalchemy import func, select
from app.models import AdminAudit, Attendance, AttendanceEvent, ClassSession, CourseResource, FocusEvent
from conftest import login


def count(client, model):
    with client.db_factory() as db:
        return db.scalar(select(func.count()).select_from(model))


def test_teacher_deletes_one_session_with_its_records(client):
    teacher = login(client, "teacher@focuslock.test")
    # session 3 is the ended demo session with attendance and an audit event
    assert count(client, Attendance) == 1 and count(client, AttendanceEvent) == 1
    response = client.request("DELETE", "/sessions/3", headers=teacher, json={"reason": "Demo cleanup before launch"})
    assert response.status_code == 200 and response.json() == {"deleted": 1}
    assert count(client, Attendance) == 0 and count(client, AttendanceEvent) == 0
    ids = [row["id"] for row in client.get("/sessions", headers=teacher).json()]
    assert 3 not in ids and 1 in ids
    with client.db_factory() as db:
        audit = db.scalar(select(AdminAudit).where(AdminAudit.action == "session_deleted"))
        assert audit is not None and audit.target_id == "3" and "cleanup" in audit.reason


def test_delete_requires_reason_and_ownership(client):
    teacher = login(client, "teacher@focuslock.test")
    assert client.request("DELETE", "/sessions/3", headers=teacher, json={"reason": "short"}).status_code == 422
    other = login(client, "teacher2@focuslock.test")
    assert client.request("DELETE", "/sessions/3", headers=other, json={"reason": "Not my course at all"}).status_code == 404
    student = login(client)
    assert client.request("DELETE", "/sessions/3", headers=student, json={"reason": "Students cannot delete"}).status_code == 403


def test_running_session_must_be_ended_first(client):
    teacher = login(client, "teacher@focuslock.test")
    assert client.post("/sessions/1/start", headers=teacher).status_code == 200
    blocked = client.request("DELETE", "/sessions/1", headers=teacher, json={"reason": "Trying to delete while running"})
    assert blocked.status_code == 409
    assert client.request("DELETE", "/courses/1/sessions", headers=teacher, json={"reason": "Trying to delete all while running"}).status_code == 409
    assert client.post("/sessions/1/end", headers=teacher).status_code == 200
    assert client.request("DELETE", "/sessions/1", headers=teacher, json={"reason": "Ended, so deletion is allowed"}).status_code == 200


def test_delete_all_sessions_in_a_course_only(client):
    teacher = login(client, "teacher@focuslock.test")
    other = login(client, "teacher2@focuslock.test")
    result = client.request("DELETE", "/courses/1/sessions", headers=teacher, json={"reason": "Clearing the demo term"})
    assert result.status_code == 200 and result.json()["deleted"] == 2
    assert client.get("/sessions", headers=teacher).json() == []
    assert len(client.get("/sessions", headers=other).json()) == 1
    assert client.request("DELETE", "/courses/1/sessions", headers=other, json={"reason": "Not my course either"}).status_code == 404
