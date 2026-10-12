from datetime import date, datetime, timedelta, timezone
from uuid import uuid4
from app.models import Attendance, ClassSession, FocusEvent
from conftest import login


def day(offset):
    return (date.today() + timedelta(days=offset)).isoformat()


def schedule(client, teacher, offset=6, slots=2, room="B-12"):
    ids = [slot["id"] for slot in client.get("/slots", headers=teacher).json()["slots"]][:slots]
    response = client.post("/sessions/bulk", headers=teacher, json={"course_id": 1, "title": "Algorithms", "room_code": room, "dates": [day(offset)], "slot_ids": ids})
    assert response.status_code == 201, response.text
    return response.json()["created"]


def test_student_timetable_shows_room_teacher_and_period_name(client):
    teacher = login(client, "teacher@focuslock.test")
    schedule(client, teacher)
    student = login(client)
    table = client.get(f"/timetable?start={day(6)}&days=1", headers=student).json()
    assert table["timezone"] == "Asia/Kolkata" and len(table["entries"]) == 2
    first = table["entries"][0]
    assert (first["room_code"], first["teacher"], first["course_code"], first["period"]) == ("B-12", "Alex Morgan", "CS101", "Period 1")
    assert table["entries"][1]["period"] == "Period 2" and first["attendance"] is None and first["date"] == day(6)
    assert client.get(f"/timetable?start={day(5)}&days=1", headers=student).json()["entries"] == []
    wide = client.get(f"/timetable?start={day(0)}&days=14", headers=student).json()["entries"]
    assert [row["date"] for row in wide].count(day(6)) == 2 and wide == sorted(wide, key=lambda row: row["starts_at"])


def test_timetable_only_shows_a_students_own_courses_and_a_teachers_own_classes(client):
    teacher = login(client, "teacher@focuslock.test")
    schedule(client, teacher)
    outsider = login(client, "student3@focuslock.test")  # enrolled in course 2 only
    assert [row["course_code"] for row in client.get(f"/timetable?start={day(6)}&days=1", headers=outsider).json()["entries"]] == []
    mine = client.get(f"/timetable?start={day(6)}&days=1", headers=teacher).json()["entries"]
    assert len(mine) == 2 and mine[0]["groups"] == []
    assert client.get(f"/timetable?start={day(6)}&days=1", headers=login(client, "teacher2@focuslock.test")).json()["entries"] == []
    assert client.get("/timetable", headers=login(client, "admin@focuslock.test")).status_code == 403
    assert client.get("/timetable?days=0", headers=teacher).status_code == 422


def add_course_history(client):
    """Course 1 gets four more ended sessions; Jordan (3) always attends, Taylor (4) attended once then stopped."""
    ids = []
    with client.db_factory() as db:
        for number in range(4):
            start = datetime.now(timezone.utc) - timedelta(days=10 - number)
            row = ClassSession(course_id=1, title=f"Lecture {number + 1}", starts_at=start, ends_at=start + timedelta(hours=1), state="ended", room_code="A101")
            db.add(row)
            db.flush()
            ids.append(row.id)
        now = datetime.now(timezone.utc)
        for index, session_id in enumerate(ids):
            db.add(Attendance(session_id=session_id, student_id=3, course_id=1, status="present", method="qr" if index == 0 else "teacher_confirmed", confirmed_by=1, updated_at=now))
            db.add(Attendance(session_id=session_id, student_id=4, course_id=1, status="present" if index == 0 else "absent", method="teacher_confirmed", confirmed_by=1, updated_at=now))
        db.add(FocusEvent(id=str(uuid4()), session_id=ids[0], student_id=3, action="started", recorded_at=now))
        db.add(FocusEvent(id=str(uuid4()), session_id=ids[0], student_id=4, action="started", recorded_at=now))
        db.add(FocusEvent(id=str(uuid4()), session_id=ids[0], student_id=4, action="exited", recorded_at=now))
        for _ in range(2):
            db.add(FocusEvent(id=str(uuid4()), session_id=ids[1], student_id=3, action="backgrounded", recorded_at=now))
        db.commit()
    return ids


def test_course_analytics_counts_attendance_focus_quits_and_students_who_stopped(client):
    teacher = login(client, "teacher@focuslock.test")
    ids = add_course_history(client)
    body = client.get("/courses/1/analytics", headers=teacher).json()
    summary = body["summary"]
    assert summary["students"] == 2 and summary["sessions"] == 6 and summary["held"] == 5
    assert summary["present_total"] == 6 and summary["attendance_rate"] == 60.0 and summary["average_present"] == 1.2
    assert summary["students_who_quit_focus"] == 1 and summary["quit_focus_events"] == 1 and summary["interruptions"] == 2
    assert summary["joined_focus"] == 2 and summary["stopped_attending"] == 1 and summary["never_attended"] == 0
    assert body["bands"] == {"90+": 1, "75-89": 0, "50-74": 0, "under 50": 1}
    people = {row["name"]: row for row in body["students"]}
    assert people["Jordan Patel"]["flag"] == "ok" and people["Jordan Patel"]["rate"] == 100.0 and people["Jordan Patel"]["interruptions"] == 2
    taylor = people["Taylor Chen"]
    assert (taylor["flag"], taylor["present"], taylor["absent"], taylor["quit_focus"], taylor["last_status"]) == ("stopped", 1, 3, 1, None)  # nothing recorded for the latest session
    rows = {row["id"]: row for row in body["sessions"]}
    first = rows[ids[0]]
    assert (first["present"], first["via_qr"], first["joined_focus"], first["quit_focus"], first["rate"]) == (2, 1, 2, 1, 100.0)
    assert rows[ids[1]]["interrupted_students"] == 1 and rows[ids[1]]["interruptions"] == 2 and rows[ids[1]]["absent"] == 1
    assert rows[1]["held"] is False and rows[1]["rate"] is None  # the seeded "ready" session was never opened


def test_analytics_is_private_to_the_courses_teacher_and_handles_an_empty_course(client):
    assert client.get("/courses/1/analytics", headers=login(client, "teacher2@focuslock.test")).status_code == 404
    assert client.get("/courses/1/analytics", headers=login(client)).status_code == 403
    assert client.get("/courses/1/analytics").status_code == 401
    teacher2 = login(client, "teacher2@focuslock.test")
    empty = client.get("/courses/2/analytics", headers=teacher2).json()
    assert empty["summary"]["held"] == 0 and empty["summary"]["attendance_rate"] is None
    assert all(row["flag"] == "ok" for row in empty["students"])


def test_student_analytics_weekly_monthly_and_overall(client):
    from app.models import FocusExitRequest
    teacher = login(client, "teacher@focuslock.test")
    ids = add_course_history(client)
    with client.db_factory() as db:
        db.add(FocusExitRequest(session_id=ids[0], student_id=4, kind="exit", status="approved", created_at=datetime.now(timezone.utc)))
        db.commit()
    body = client.get("/courses/1/students/4/analytics", headers=teacher).json()
    totals = body["totals"]
    assert body["student"]["name"] == "Taylor Chen" and body["student"]["kind"] == "regular"
    assert totals["present"] == 1 and totals["absent"] == 3 and totals["breaks"] == 1 and totals["breaks_approved"] == 1 and totals["quit_focus"] == 1
    assert totals["rate"] == round(100 * 1 / totals["held"], 1)
    assert sum(row["sessions"] for row in body["weekly"]) == totals["held"] and sum(row["present"] for row in body["monthly"]) == 1
    assert len(body["history"]) == totals["held"]
    overall = client.get("/courses/1/analytics", headers=teacher).json()
    assert overall["summary"]["breaks"] == 1 and sum(row["sessions"] for row in overall["weekly"]) == overall["summary"]["held"]
    assert overall["students"][0]["breaks"] in (0, 1) and overall["monthly"]
    assert client.get("/courses/1/students/5/analytics", headers=teacher).status_code == 404  # not in this course
    assert client.get("/courses/1/students/4/analytics", headers=login(client, "teacher2@focuslock.test")).status_code in (403, 404)
    assert client.get("/courses/1/students/4/analytics", headers=login(client)).status_code == 403


def test_term_reports_for_student_and_teacher(client):
    teacher = login(client, "teacher@focuslock.test")
    add_course_history(client)
    mine = client.get("/attendance/me/summary", headers=login(client)).json()
    course = next(c for t in mine["terms"] for c in t["courses"] if c["course_id"] == 1)
    assert course["present"] >= 4 and course["held"] >= 4 and course["rate"] == round(100 * course["present"] / course["held"], 1)
    assert all(day["date"] for day in course["days"]) and course["days"][0]["status"] in (None, "present", "absent", "excused")
    assert client.get("/attendance/me/summary", headers=teacher).status_code == 403
    report = client.get("/reports/teacher", headers=teacher).json()
    assert report["term"] in report["terms"] and report["courses"]
    first = next(c for c in report["courses"] if c["id"] == 1)
    assert first["held"] >= 4 and first["absent"] >= 3
    day = next(row for row in report["daily"] if row["course_id"] == 1 and row["absent"] >= 1)
    assert day["present"] + day["absent"] + day["excused"] == day["expected"]
    assert client.get("/reports/teacher?term=nonexistent", headers=teacher).json()["term"] == report["term"]
    assert client.get("/reports/teacher", headers=login(client)).status_code == 403
