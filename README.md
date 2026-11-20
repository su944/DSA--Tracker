# DSA Tracker

A local-first workspace for data structures and algorithms practice. Use Topics to organize your catalog, then use Problems to add, filter, and update your practice records. Use Goals to track solve targets and Daily Planner to organize practice by day or week. The Dashboard shows catalog totals, today’s plan and goals, and practice analytics alongside the local connection status.

See the [implementation plan](docs/IMPLEMENTATION_PLAN.md) and [architecture](docs/ARCHITECTURE.md) for the build sequence and system boundaries.

## Prerequisites

- JDK 21 and Maven 3.9 or later. Confirm that `mvn -version` reports Java 21.
- Node.js 24 LTS and npm 11. Confirm with `node --version` and `npm --version`.
- Available local ports 8080 for Spring Boot and 5173 for Vite.
- Internet access on the first dependency install. SQLite is bundled through its JDBC driver and requires no separate database server.

## Run in development

Start the backend in one terminal from the repository root.

```sh
cd backend
mvn spring-boot:run
```

Start the frontend in a second terminal from the repository root.

```sh
cd frontend
npm ci
npm run dev
```

Open [http://127.0.0.1:5173](http://127.0.0.1:5173). The Dashboard checks `GET /api/health` when opened and offers **Check again** to retry. Vite proxies `/api` requests to the Spring Boot backend. Direct development requests from `http://localhost:5173` and `http://127.0.0.1:5173` are allowed by CORS.

Check the backend directly with `curl -i http://127.0.0.1:8080/api/health`. The API returns HTTP 200 with `{"status":"UP"}` when SQLite responds, or HTTP 503 with `{"status":"DOWN"}` when the connection check fails.

## Local database

The commands above create `backend/data/tracker.sqlite` on first startup. The same file is reused across runs. Flyway applies ordered SQL migrations from `backend/src/main/resources/db/migration/` and records them in the database. V1 establishes the migration baseline. V2 adds the catalog, topic links, and one progress record per problem. V3 adds goals and scheduled problems. Spring Data JPA is configured with the Hibernate community SQLite dialect. Flyway owns schema changes.

- Set `TRACKER_DB_PATH` to use a different database file. Relative paths resolve from the backend process working directory.
- Set `TRACKER_TIME_ZONE` to an IANA time zone such as `Asia/Kolkata`. The default is the host time zone.
- Keep local database and environment files out of source control.

## Catalog and progress API

- `GET /api/problems` lists problems. Filters are `q` (LeetCode ID or title), repeatable `topic` IDs (matches any selected topic), `difficulty`, and `status`. Combine filters with zero-based `page`, `size` from 1 to 100, and `sort=field,asc|desc`. Supported fields are `id`, `leetcodeId`, `title`, `createdAt`, and `updatedAt`. Defaults are page 0, size 20, and ascending LeetCode ID.
- `POST /api/problems` creates a problem. `GET`, `PUT`, and `DELETE /api/problems/{id}` read, replace catalog fields, and delete by the internal ID. Create and edit accept `leetcodeId`, `title`, `url`, `difficulty`, and at least one existing ID in `topicIds`. Links normalize to `https://leetcode.com/problems/{slug}/`.
- `PATCH /api/problems/{id}/progress` accepts `status`, `attemptedDate`, `solvedDate`, and `comments`. Dates use `YYYY-MM-DD`. Omitted fields keep applicable values, and newly required dates default to today in the tracker time zone. Solved can have an empty attempted date. Attempted clears the solved date, and Not Started clears both dates. Send an empty string to clear comments.
- `GET` and `POST /api/topics` list and create topics. `GET`, `PUT`, and `DELETE /api/topics/{id}` read, rename, and delete a topic. Names are trimmed and compared case-insensitively. Remove a topic from all linked problems before deleting it. Deleting a problem removes its progress and topic links, and keeps the topics.
- Both resources also support the `/api/v1` prefix. The API returns `{ "code": "...", "message": "...", "fields": {} }` for invalid input, missing records, and catalog conflicts.

## Goals and planner API

- `GET` and `POST /api/goals` list and create goals. `GET`, `PUT`, and `DELETE /api/goals/{id}` read, replace, and delete a goal. Send `name`, `type`, `startDate`, a positive `targetCount`, and an optional `topicId`. Supply `endDate` for `CUSTOM`. `DAILY` ends on its start date, and `WEEKLY` runs Monday through Sunday.
- `GET /api/goals/{id}/progress` counts distinct problems with current status Solved and a solved date within the inclusive period. A topic restriction uses each problem's current topic membership. Goal list and detail responses include the same progress.
- `GET /api/planner?view=DAY|WEEK&date=YYYY-MM-DD` returns the selected day or Monday–Sunday week, with scheduled, completed, and skipped counts. The default date is today in the tracker time zone. Scheduled totals include skipped tasks, which are also reported separately.
- `POST /api/planner` schedules an existing problem with `problemId` and `date`. `GET /api/planner/{id}` reads an item, `PUT /api/planner/{id}` reschedules it with `date`, and `DELETE /api/planner/{id}` removes it. Scheduling the same problem twice on one date returns `409`.
- `PATCH /api/planner/{id}/skip` accepts `skipped` to skip or restore an item. `POST /api/planner/{id}/carry-forward` moves an incomplete item to a later `date` and clears its skipped state. Completed items can be removed. Moving and skipping apply to incomplete items.
- An active item is completed when the problem is currently Solved and its solved date matches the scheduled date. Correcting the solved date or reopening the problem updates the planner on its next read. Scheduling changes preserve problem progress.
- The `/api/v1` prefix also works for goals and planner. Deleting a problem removes its planner items. Deleting a topic used by a goal requires removing that restriction first.

## Dashboard and analytics API

- `GET /api/dashboard/summary` reports current problem totals by status. Each problem contributes once, regardless of its topics.
- `GET /api/dashboard/today` returns today’s planner items and active goal progress in the tracker time zone.
- `GET /api/analytics/topics` reports the total problems, solves in the selected range, and currently unsolved problems per topic. A problem can count once for each of its topics.
- `GET /api/analytics/difficulty` reports solved counts by difficulty, and `GET /api/analytics/activity` groups current solves by their saved solved date. Bounded activity periods include days with zero solves when the window has any solves. All time lists dates with solves.
- `GET /api/analytics/recent` includes separate recently solved and recently modified lists. The latest catalog or progress update determines modification time. The optional `limit` is 1 to 50 per list and defaults to 10.
- The analytics endpoints accept `range=7|30|90|all` and default to all time. Bounded ranges include today. Undated solves count toward current status totals and all-time topic and difficulty totals. Date-based solve metrics require a saved solved date. The `/api/v1` prefix also works for reporting.

## Verify and package

From the repository root, run the frontend checks and build before packaging the backend so Maven can include the built frontend in the JAR.

```sh
cd frontend
npm ci
npm test
npm run build

cd ../backend
mvn clean verify
java -jar target/dsa-tracker-0.1.0-SNAPSHOT.jar
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080) to use the packaged app. Navigation and health checks use local assets and same-origin requests.
