from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo
from sqlalchemy import func, select
from app.models import ClassSession
from conftest import login


def future(days):
    return (date.today() + timedelta(days=days)).isoformat()


def slot_ids(client, headers, count=2):
    return [slot["id"] for slot in client.get("/slots", headers=headers).json()["slots"]][:count]


def test_everyone_signed_in_sees_active_slots_with_the_campus_timezone(client):
    body = client.get("/slots", headers=login(client)).json()
    assert body["timezone"] == "Asia/Kolkata"
    assert [slot["label"] for slot in body["slots"]][:2] == ["Period 1", "Period 2"]
    assert body["slots"][0]["starts"] == "09:00" and body["slots"][0]["ends"] == "09:50"
    assert client.get("/slots").status_code == 401


def test_only_admins_change_slots(client):
    payload = {"label": "Zero hour", "starts": "07:30", "ends": "08:30"}
    for email in ("teacher@focuslock.test", "student@focuslock.test"):
        headers = login(client, email)
        assert client.post("/admin/slots", headers=headers, json=payload).status_code == 403
        assert client.get("/admin/slots", headers=headers).status_code == 403
    admin = login(client, "admin@focuslock.test")
    created = client.post("/admin/slots", headers=admin, json=payload)
    assert created.status_code == 201 and created.json()["starts"] == "07:30"
    teacher = login(client, "teacher@focuslock.test")
    assert "Zero hour" in [slot["label"] for slot in client.get("/slots", headers=teacher).json()["slots"]]


def test_slot_rules_overlap_order_and_duplicate_names(client):
    admin = login(client, "admin@focuslock.test")
    assert client.post("/admin/slots", headers=admin, json={"label": "Clash", "starts": "09:30", "ends": "10:00"}).status_code == 409
    assert client.post("/admin/slots", headers=admin, json={"label": "Backwards", "starts": "12:00", "ends": "11:00"}).status_code == 422
    assert client.post("/admin/slots", headers=admin, json={"label": "Period 1", "starts": "06:00", "ends": "06:30"}).status_code == 409
    assert client.post("/admin/slots", headers=admin, json={"label": "Bad", "starts": "25:00", "ends": "26:00"}).status_code == 422
    # a switched-off slot may overlap, and switching it back on re-checks
    off = client.post("/admin/slots", headers=admin, json={"label": "Spare", "starts": "09:30", "ends": "10:00", "active": False})
    assert off.status_code == 201
    again = client.put(f"/admin/slots/{off.json()['id']}", headers=admin, json={"label": "Spare", "starts": "09:30", "ends": "10:00", "active": True})
    assert again.status_code == 409


def test_admin_can_edit_hide_and_delete_a_slot(client):
    admin = login(client, "admin@focuslock.test")
    teacher = login(client, "teacher@focuslock.test")
    first = client.get("/admin/slots", headers=admin).json()["slots"][0]
    edited = client.put(f"/admin/slots/{first['id']}", headers=admin, json={"label": "Morning", "starts": "08:45", "ends": "09:35", "active": True})
    assert edited.status_code == 200 and edited.json()["label"] == "Morning"
    hidden = client.put(f"/admin/slots/{first['id']}", headers=admin, json={"label": "Morning", "starts": "08:45", "ends": "09:35", "active": False})
    assert hidden.json()["active"] is False
    assert first["id"] not in [slot["id"] for slot in client.get("/slots", headers=teacher).json()["slots"]]
    assert first["id"] in [slot["id"] for slot in client.get("/admin/slots", headers=admin).json()["slots"]]
    assert client.delete(f"/admin/slots/{first['id']}", headers=admin).status_code == 204
    assert client.delete(f"/admin/slots/{first['id']}", headers=admin).status_code == 404


def test_teacher_schedules_many_sessions_from_slots_in_campus_time(client):
    teacher = login(client, "teacher@focuslock.test")
    ids = slot_ids(client, teacher, 2)
    result = client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "Algorithms lecture", "room_code": "B-12",
                                                                   "dates": [future(10), future(11), future(10)], "slot_ids": ids})
    assert result.status_code == 201, result.text
    body = result.json()
    assert len(body["created"]) == 4 and body["skipped"] == []
    first = min(body["created"], key=lambda row: row["starts_at"])
    day = date.today() + timedelta(days=10)
    expected = datetime(day.year, day.month, day.day, 9, 0, tzinfo=ZoneInfo("Asia/Kolkata")).astimezone(timezone.utc)
    assert datetime.fromisoformat(first["starts_at"]) == expected
    assert first["room_code"] == "B-12" and first["state"] == "ready"
    assert len({row["title"] for row in body["created"]}) == 1


def test_repeating_the_same_request_skips_clashes_instead_of_duplicating(client):
    teacher = login(client, "teacher@focuslock.test")
    payload = {"course_id": 1, "title": "Weekly lab", "dates": [future(20)], "slot_ids": slot_ids(client, teacher, 2)}
    assert len(client.post("/sessions/bulk", headers=teacher, json=payload).json()["created"]) == 2
    again = client.post("/sessions/bulk", headers=teacher, json=payload).json()
    assert again["created"] == [] and len(again["skipped"]) == 2
    assert all("already has a session" in item["reason"] for item in again["skipped"])
    with client.db_factory() as db:
        assert db.scalar(select(func.count()).select_from(ClassSession).where(ClassSession.title == "Weekly lab")) == 2


def test_past_dates_are_skipped_and_unknown_or_inactive_slots_rejected(client):
    teacher = login(client, "teacher@focuslock.test")
    ids = slot_ids(client, teacher, 1)
    past = client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "Backdated", "dates": [future(-3)], "slot_ids": ids}).json()
    assert past["created"] == [] and past["skipped"][0]["reason"] == "Already started or in the past"
    assert client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "Nope", "dates": [future(5)], "slot_ids": [9999]}).status_code == 422
    admin = login(client, "admin@focuslock.test")
    slot = client.get("/admin/slots", headers=admin).json()["slots"][0]
    client.put(f"/admin/slots/{slot['id']}", headers=admin, json={"label": slot["label"], "starts": slot["starts"], "ends": slot["ends"], "active": False})
    assert client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "Hidden", "dates": [future(5)], "slot_ids": [slot["id"]]}).status_code == 422


def test_only_the_courses_teacher_can_bulk_schedule_and_limits_hold(client):
    teacher = login(client, "teacher@focuslock.test")
    ids = slot_ids(client, teacher, 2)
    other = login(client, "teacher2@focuslock.test")
    assert client.post("/sessions/bulk", headers=other, json={"course_id": 1, "title": "Not mine", "dates": [future(5)], "slot_ids": ids}).status_code == 404
    student = login(client)
    assert client.post("/sessions/bulk", headers=student, json={"course_id": 1, "title": "Students cannot", "dates": [future(5)], "slot_ids": ids}).status_code == 403
    many = [(date.today() + timedelta(days=30 + n)).isoformat() for n in range(60)]
    assert client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "Too many", "dates": many, "slot_ids": slot_ids(client, teacher, 7)}).status_code == 422
    assert client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "x", "dates": [future(5)], "slot_ids": ids}).status_code == 422
    assert client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "Extra field", "dates": [future(5)], "slot_ids": ids, "starts_at": "now"}).status_code == 422
