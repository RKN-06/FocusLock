from datetime import datetime, timezone
from sqlalchemy import select
from app.models import Attendance, Enrollment
from conftest import login

NOW = datetime.now(timezone.utc)


def make_group(client, admin, name="CSE Year 3"):
    response = client.post("/admin/groups", headers=admin, json={"name": name})
    assert response.status_code == 201, response.text
    return response.json()["id"]


def enrolment(client, course_id, student_id):
    with client.db_factory() as db:
        return db.get(Enrollment, (course_id, student_id))


def test_admin_groups_students_by_email_and_reports_what_it_could_not_add(client):
    admin = login(client, "admin@focuslock.test")
    group = make_group(client, admin)
    result = client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": [
        "student2@focuslock.test", " STUDENT3@focuslock.test ", "student2@focuslock.test", "nobody@focuslock.test", "teacher@focuslock.test"]})
    assert result.status_code == 200, result.text
    body = result.json()
    assert body["added"] == ["student2@focuslock.test", "student3@focuslock.test"]
    assert body["unknown"] == ["nobody@focuslock.test"] and body["not_student"] == ["teacher@focuslock.test"]
    again = client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": ["student2@focuslock.test"]}).json()
    assert again["added"] == [] and again["already"] == ["student2@focuslock.test"]
    detail = client.get(f"/admin/groups/{group}", headers=admin).json()
    assert [m["name"] for m in detail["members"]] == ["Casey Kim", "Taylor Chen"]  # listed by name
    listing = client.get("/admin/groups", headers=admin).json()
    assert listing[0]["members"] == 2 and listing[0]["courses"] == 0
    students = {row["email"]: row for row in client.get("/admin/students", headers=admin).json()}
    assert students["student2@focuslock.test"]["groups"] == ["CSE Year 3"] and students["student@focuslock.test"]["groups"] == []


def test_linking_a_group_to_a_course_enrols_its_members_and_teacher_sees_groups(client):
    admin = login(client, "admin@focuslock.test")
    teacher = login(client, "teacher@focuslock.test")
    group = make_group(client, admin)
    client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": ["student2@focuslock.test", "student3@focuslock.test"]})
    assert enrolment(client, 1, 5) is None
    linked = client.put("/admin/courses/1/groups", headers=admin, json={"group_ids": [group]})
    assert linked.status_code == 200, linked.text
    assert linked.json()["sync"]["added"] == 1  # Casey is new; Taylor was already enrolled by hand
    assert enrolment(client, 1, 5).source == "group" and enrolment(client, 1, 4).source == "manual"
    course = next(row for row in client.get("/courses", headers=teacher).json() if row["id"] == 1)
    assert course["students"] == 3 and course["groups"] == [{"id": group, "name": "CSE Year 3", "members": 2}]
    assert 1 in [row["id"] for row in client.get("/courses", headers=login(client, "student3@focuslock.test")).json()]
    # a student added to the group later is enrolled in every linked course
    client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": ["student@focuslock.test"]})
    assert enrolment(client, 1, 3) is not None


def test_removing_members_only_unenrols_group_enrolments_without_attendance(client):
    admin = login(client, "admin@focuslock.test")
    group = make_group(client, admin)
    client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": ["student2@focuslock.test", "student3@focuslock.test"]})
    client.put("/admin/courses/1/groups", headers=admin, json={"group_ids": [group]})
    # Taylor (manual) stays; Casey (group) goes when there is no attendance
    assert client.delete(f"/admin/groups/{group}/members/4", headers=admin).json() == {"removed": 0, "kept": 0}
    assert enrolment(client, 1, 4) is not None
    assert client.delete(f"/admin/groups/{group}/members/5", headers=admin).json() == {"removed": 1, "kept": 0}
    assert enrolment(client, 1, 5) is None
    # once a student has attendance the enrolment is kept so records stay valid
    client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": ["student3@focuslock.test"]})
    with client.db_factory() as db:
        db.add(Attendance(session_id=3, student_id=5, course_id=1, status="present", method="teacher_confirmed", confirmed_by=1, updated_at=NOW))
        db.commit()
    assert client.delete(f"/admin/groups/{group}/members/5", headers=admin).json() == {"removed": 0, "kept": 1}
    assert enrolment(client, 1, 5) is not None
    with client.db_factory() as db:
        assert db.scalar(select(Attendance).where(Attendance.student_id == 5, Attendance.session_id == 3)) is not None
    assert client.delete(f"/admin/groups/{group}/members/5", headers=admin).status_code == 404


def test_adding_courses_to_a_teacher_can_include_the_student_groups(client):
    admin = login(client, "admin@focuslock.test")
    teacher = login(client, "teacher@focuslock.test")
    me = client.get("/me", headers=teacher).json()
    group = make_group(client, admin, "ECE Year 2")
    client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": ["student@focuslock.test", "student2@focuslock.test", "student3@focuslock.test"]})
    result = client.post("/admin/courses/bulk", headers=admin, json={"teacher_id": me["id"], "term": "Autumn 2026", "group_ids": [group],
        "courses": [{"code": "EC201", "title": "Circuits"}, {"code": "EC202", "title": "Signals"}]})
    assert result.status_code == 201, result.text
    body = result.json()
    assert body["enrolled"] == 6
    assert all(row["students"] == 3 and row["groups"][0]["name"] == "ECE Year 2" for row in body["created"])
    listed = client.get(f"/admin/teachers/{me['id']}/courses", headers=admin).json()
    assert {row["code"] for row in listed if row["groups"]} == {"EC201", "EC202"}
    missing = client.post("/admin/courses/bulk", headers=admin, json={"teacher_id": me["id"], "term": "T", "group_ids": [999], "courses": [{"code": "X", "title": "Y"}]})
    assert missing.status_code == 404


def test_group_rules_names_permissions_and_deleting_a_group(client):
    admin = login(client, "admin@focuslock.test")
    group = make_group(client, admin, "Mech Year 1")
    assert client.post("/admin/groups", headers=admin, json={"name": "mech year 1"}).status_code == 409
    assert client.put(f"/admin/groups/{group}", headers=admin, json={"name": "Mechanical Year 1"}).json()["name"] == "Mechanical Year 1"
    for email in ("teacher@focuslock.test", "student@focuslock.test"):
        headers = login(client, email)
        assert client.post("/admin/groups", headers=headers, json={"name": "Nope"}).status_code == 403
        assert client.get("/admin/groups", headers=headers).status_code == 403
        assert client.put("/admin/courses/1/groups", headers=headers, json={"group_ids": []}).status_code == 403
    assert client.put("/admin/courses/1/groups", headers=admin, json={"group_ids": [4242]}).status_code == 404
    client.post(f"/admin/groups/{group}/members", headers=admin, json={"emails": ["student3@focuslock.test"]})
    client.put("/admin/courses/1/groups", headers=admin, json={"group_ids": [group]})
    assert enrolment(client, 1, 5) is not None
    assert client.delete(f"/admin/groups/{group}", headers=admin).json()["removed"] == 1
    assert enrolment(client, 1, 5) is None and client.get(f"/admin/groups/{group}", headers=admin).status_code == 404


def test_individual_students_and_registration_types(client):
    admin = login(client, "admin@focuslock.test")
    teacher = login(client, "teacher@focuslock.test")
    added = client.post("/admin/courses/1/students", headers=admin, json={"students": [
        {"email": "student3@focuslock.test", "kind": "reregistered"}, {"email": "student@focuslock.test", "kind": "other"},
        {"email": "nobody@focuslock.test"}, {"email": "teacher@focuslock.test"}]})
    assert added.status_code == 200, added.text
    body = added.json()
    assert body["added"] == ["student3@focuslock.test"] and body["updated"] == ["student@focuslock.test"]
    assert body["unknown"] == ["nobody@focuslock.test"] and body["not_student"] == ["teacher@focuslock.test"]
    listing = {row["email"]: row for row in client.get("/admin/courses/1/students", headers=admin).json()}
    assert listing["student3@focuslock.test"]["kind"] == "reregistered" and listing["student@focuslock.test"]["kind"] == "other"
    assert {row["email"]: row["kind"] for row in client.get("/courses/1/roster", headers=teacher).json()}["student3@focuslock.test"] == "reregistered"
    assert client.post("/admin/courses/1/students", headers=admin, json={"students": [{"email": "x@y.test", "kind": "bogus"}]}).status_code == 422
    assert client.put("/admin/courses/1/students/5", headers=admin, json={"kind": "regular"}).json()["kind"] == "regular"
    assert client.delete("/admin/courses/1/students/5", headers=admin).status_code == 200
    assert client.post("/admin/courses/1/students", headers=teacher, json={"students": [{"email": "student3@focuslock.test"}]}).status_code == 403


def test_group_members_carry_a_kind_into_the_course_enrolment(client):
    admin = login(client, "admin@focuslock.test")
    group = make_group(client, admin)
    client.post(f"/admin/groups/{group}/members", headers=admin, json={"members": [
        {"email": "student3@focuslock.test", "kind": "reregistered"}, {"email": "student2@focuslock.test"}]})
    client.put("/admin/courses/1/groups", headers=admin, json={"group_ids": [group]})
    assert enrolment(client, 1, 5).kind == "reregistered" and enrolment(client, 1, 5).source == "group"
    members = {m["email"]: m["kind"] for m in client.get(f"/admin/groups/{group}", headers=admin).json()["members"]}
    assert members["student2@focuslock.test"] == "regular" and members["student3@focuslock.test"] == "reregistered"
    client.put(f"/admin/groups/{group}/members/5", headers=admin, json={"kind": "other"})
    assert enrolment(client, 1, 5).kind == "other"
    assert client.delete("/admin/courses/1/students/5", headers=admin).status_code == 409  # comes from the group
