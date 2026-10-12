from conftest import login


def rows(*pairs):
    return [{"code": code, "title": title} for code, title in pairs]


def test_admin_adds_many_courses_to_a_teacher_who_then_sees_them(client):
    admin = login(client, "admin@focuslock.test")
    teacher = login(client, "teacher@focuslock.test")
    me = client.get("/me", headers=teacher).json()
    before = len(client.get("/courses", headers=teacher).json())
    result = client.post("/admin/courses/bulk", headers=admin, json={"teacher_id": me["id"], "term": "Autumn 2026",
        "courses": rows(("CS201", "Data Structures"), ("CS202", "Algorithms"), ("MA110", "Linear Algebra"))})
    assert result.status_code == 201, result.text
    body = result.json()
    assert [row["code"] for row in body["created"]] == ["CS201", "CS202", "MA110"] and body["skipped"] == []
    assert all(row["teacher_id"] == me["id"] and row["term"] == "Autumn 2026" for row in body["created"])
    mine = client.get("/courses", headers=teacher).json()
    assert len(mine) == before + 3
    listed = client.get(f"/admin/teachers/{me['id']}/courses", headers=admin).json()
    assert {"CS201", "CS202", "MA110"} <= {row["code"] for row in listed}
    teachers = {row["id"]: row for row in client.get("/admin/teachers", headers=admin).json()}
    assert teachers[me["id"]]["courses"] == before + 3


def test_duplicates_are_skipped_not_repeated(client):
    admin = login(client, "admin@focuslock.test")
    me = client.get("/me", headers=login(client, "teacher@focuslock.test")).json()
    payload = {"teacher_id": me["id"], "term": "Spring 2027", "courses": rows(("PH101", "Physics"), ("ph101", "Physics again"), ("PH102", "Optics"))}
    first = client.post("/admin/courses/bulk", headers=admin, json=payload).json()
    assert [row["code"] for row in first["created"]] == ["PH101", "PH102"]
    assert first["skipped"] == [{"code": "ph101", "reason": "Already on this teacher's list for that term"}]
    again = client.post("/admin/courses/bulk", headers=admin, json=payload).json()
    assert again["created"] == [] and len(again["skipped"]) == 3
    other_term = client.post("/admin/courses/bulk", headers=admin, json={**payload, "term": "Autumn 2027"}).json()
    assert len(other_term["created"]) == 2


def test_only_admins_and_only_real_teachers(client):
    me = client.get("/me", headers=login(client, "teacher@focuslock.test")).json()
    payload = {"teacher_id": me["id"], "term": "T", "courses": rows(("X1", "Course"))}
    for email in ("teacher@focuslock.test", "student@focuslock.test"):
        headers = login(client, email)
        assert client.post("/admin/courses/bulk", headers=headers, json=payload).status_code == 403
        assert client.get("/admin/teachers", headers=headers).status_code == 403
    assert client.post("/admin/courses/bulk", json=payload).status_code == 401
    admin = login(client, "admin@focuslock.test")
    student = client.get("/me", headers=login(client)).json()
    assert client.post("/admin/courses/bulk", headers=admin, json={**payload, "teacher_id": student["id"]}).status_code == 404
    assert client.post("/admin/courses/bulk", headers=admin, json={**payload, "teacher_id": 99999}).status_code == 404
    assert client.get("/admin/teachers/99999/courses", headers=admin).status_code == 404


def test_input_limits_and_extra_fields_are_rejected(client):
    admin = login(client, "admin@focuslock.test")
    me = client.get("/me", headers=login(client, "teacher@focuslock.test")).json()
    base = {"teacher_id": me["id"], "term": "T"}
    assert client.post("/admin/courses/bulk", headers=admin, json={**base, "courses": []}).status_code == 422
    too_many = rows(*[(f"C{n}", "Course") for n in range(51)])
    assert client.post("/admin/courses/bulk", headers=admin, json={**base, "courses": too_many}).status_code == 422
    assert client.post("/admin/courses/bulk", headers=admin, json={**base, "courses": rows(("", "Untitled"))}).status_code == 422
    assert client.post("/admin/courses/bulk", headers=admin, json={**base, "courses": rows(("C" * 33, "Too long"))}).status_code == 422
    assert client.post("/admin/courses/bulk", headers=admin, json={**base, "courses": rows(("A1", "Fine")), "role": "admin"}).status_code == 422
