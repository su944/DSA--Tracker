#!/usr/bin/env python3
"""Exercise the packaged app against an isolated SQLite database and backup directory."""

import argparse
import json
import re
import subprocess
import tempfile
import time
from contextlib import contextmanager
from datetime import date, timedelta
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4

REPO = Path(__file__).resolve().parents[1]
DEFAULT_JAR = REPO / "backend/target/dsa-tracker-0.1.0-SNAPSHOT.jar"
HTTP = build_opener(ProxyHandler({}))


class Api:
    def __init__(self, port):
        self.origin = f"http://127.0.0.1:{port}"

    def request(self, path, *, method="GET", data=None, status=200, headers=None, raw=False):
        headers = dict(headers or {})
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        request = Request(self.origin + path, data=data, method=method, headers=headers)
        try:
            response = HTTP.open(request, timeout=15)
        except HTTPError as failure:
            response = failure
        with response:
            body = response.read()
            assert response.status == status, (method, path, response.status, body.decode(errors="replace"))
            if raw:
                return body
            return json.loads(body) if body else None

    def create(self, path, data):
        return self.request(path, method="POST", data=data, status=201)

    def upload(self, operation, data, format_name):
        boundary = uuid4().hex
        header = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"catalog.{format_name.lower()}\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        ).encode()
        return self.request(
            f"/api/exchange/{operation}?format={format_name}",
            method="POST",
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            data=header + data + f"\r\n--{boundary}--\r\n".encode(),
        )


@contextmanager
def run_backend(jar, workspace, run_number):
    log_path = workspace / f"backend-{run_number}.log"
    with log_path.open("wb") as output:
        backend = subprocess.Popen(
            [
                "java", "-jar", str(jar), "--server.port=0", "--server.address=127.0.0.1",
                f"--tracker.database-path={workspace / 'test.sqlite'}",
                f"--tracker.backup-root={workspace / 'backups'}",
                "--tracker.time-zone=Asia/Kolkata",
            ],
            cwd=workspace,
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        try:
            for _ in range(240):
                if backend.poll() is not None:
                    raise AssertionError(f"Backend exited with {backend.returncode}")
                match = re.search(r"Tomcat started on port (\d+)", log_path.read_text(errors="replace"))
                if match:
                    client = Api(int(match[1]))
                    try:
                        if client.request("/api/health") == {"status": "UP"}:
                            break
                    except (URLError, TimeoutError):
                        pass
                time.sleep(0.25)
            else:
                raise AssertionError("Backend did not become ready")
            yield client
        except Exception:
            print(log_path.read_text(errors="replace")[-6000:])
            raise
        finally:
            if backend.poll() is None:
                backend.terminate()
                try:
                    backend.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    backend.kill()
                    backend.wait()


def read_state(api, day):
    return {
        "problems": api.request("/api/problems")["items"],
        "topics": api.request("/api/topics"),
        "goals": api.request("/api/goals"),
        "planner": api.request(f"/api/planner?date={day}")["days"],
        "revisions": {
            filter_name: api.request(f"/api/revisions?filter={filter_name}")["items"]
            for filter_name in ("DUE_TODAY", "OVERDUE", "UPCOMING", "COMPLETED", "CANCELLED")
        },
    }


def exercise(api):
    page = api.request("/", raw=True).decode()
    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', page)
    assert any(asset.endswith(".js") for asset in assets), "Build the frontend before packaging the backend"
    for asset in assets:
        assert api.request(asset, raw=True), asset
    assert api.request("/api/health", headers={"Host": "tracker.invalid"}, status=403)["code"] == "FORBIDDEN"

    day = api.request("/api/dashboard/today")["date"]
    arrays = api.create("/api/topics", {"name": "Arrays"})["id"]
    hashing = api.create("/api/topics", {"name": "Hashing"})["id"]
    fields = {
        "leetcodeId": 1, "title": "Two Sum", "url": "https://leetcode.com/problems/two-sum/",
        "difficulty": "EASY", "topicIds": [arrays, hashing],
    }
    problem_id = api.create("/api/problems", fields)["id"]
    problem = f"/api/problems/{problem_id}"
    api.request(problem, method="PUT", data={**fields, "title": "Two Sum Edited"})
    page = api.request(f"/api/problems?q=Edited&topic={arrays}&size=1")
    assert page["totalItems"] == 1 and len(page["items"][0]["topics"]) == 2
    discard = api.create("/api/problems", {**fields, "leetcodeId": 9999, "title": "Discard"})["id"]
    api.request(f"/api/problems/{discard}", method="DELETE", status=204)
    api.request(f"/api/problems/{discard}", status=404)

    goal = api.create("/api/goals", {
        "name": "Array practice", "type": "DAILY", "startDate": day, "targetCount": 1, "topicId": arrays,
    })
    assignment = {"problemId": problem_id, "date": day}
    api.create("/api/planner", assignment)
    api.request("/api/planner", method="POST", data=assignment, status=409)
    api.request(f"{problem}/progress", method="PATCH", data={"status": "ATTEMPTED", "comments": "Hash lookup"})
    api.request(f"{problem}/progress", method="PATCH", data={"status": "SOLVED", "solvedDate": day})
    assert api.request(f"/api/goals/{goal['id']}/progress")["complete"]
    dashboard = api.request("/api/dashboard/today")
    assert dashboard["plan"]["counts"]["completed"] == 1
    assert dashboard["goals"][0]["progress"]["solvedCount"] == 1
    assert api.request("/api/dashboard/summary") == {"total": 1, "solved": 1, "attempted": 0, "notStarted": 0}
    assert api.request("/api/analytics/activity?range=7")["days"][-1] == {"date": day, "solved": 1}
    assert sum(topic["solved"] for topic in api.request("/api/analytics/topics")) == 2

    revision = api.create("/api/revisions", assignment)["id"]
    next_date = (date.fromisoformat(day) + timedelta(days=1)).isoformat()
    task = api.request(f"/api/revisions/{revision}", method="PUT", data={"date": next_date})
    assert task["scheduledDate"] == next_date
    task = api.request(f"/api/revisions/{revision}/complete", method="POST")
    assert task["status"] == "COMPLETED"
    progress = api.request(problem)["progress"]
    assert progress["solvedDate"] == day and progress["comments"] == "Hash lookup"

    imported = (
        "leetcodeId,title,url,difficulty,topics\n"
        "242,Valid Anagram,https://leetcode.com/problems/valid-anagram/,EASY,Hashing\n"
        "99,Invalid,https://example.invalid/problems/invalid/,EASY,Arrays\n"
    ).encode()
    preview = api.upload("preview", imported, "CSV")
    assert preview["creates"] == 1 and preview["invalid"] == 1
    applied = api.upload("import", imported, "CSV")
    assert applied["created"] == 1 and applied["failed"] == 1
    for format_name in ("CSV", "JSON"):
        exported = api.request(f"/api/exchange/export?format={format_name}", raw=True)
        assert api.upload("import", exported, format_name)["skipped"] == 2
    assert api.request(problem)["progress"] == progress
    return day


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    jar = parser.parse_args().jar.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="dsa-tracker-smoke-") as directory:
        workspace = Path(directory)
        with run_backend(jar, workspace, 1) as api:
            day = exercise(api)
            expected = read_state(api, day)
            backup = api.request("/api/backups", method="POST", status=201)
        with run_backend(jar, workspace, 2) as api:
            assert read_state(api, day) == expected, "Persisted data changed after restart"
            api.create("/api/topics", {"name": "Created after backup"})
            restore = f"/api/backups/{backup['id']}/restore"
            api.request(restore, method="POST", data={"confirmation": "yes"}, status=400)
            api.request(restore, method="POST", data={"confirmation": "RESTORE"}, status=202)
            assert len(api.request("/api/topics")) == len(expected["topics"]) + 1
        with run_backend(jar, workspace, 3) as api:
            assert read_state(api, day) == expected, "Full restore did not recover the saved tracker state"
            listing = api.request("/api/backups")
            assert listing["pendingRestoreId"] is None and len(listing["backups"]) == 2
    print("Packaged smoke test passed, including a restart and full restore in a temporary database.")


if __name__ == "__main__":
    main()
