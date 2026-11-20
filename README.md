# DSA Tracker

A local-first workspace for data structures and algorithms practice. Use Topics to organize your catalog, then use Problems to add, filter, and update your practice records. Use Goals to track solve targets and Daily Planner to organize practice by day or week. The Dashboard shows catalog totals, today’s plan and goals, and practice analytics alongside the local connection status.

See the [implementation plan](docs/IMPLEMENTATION_PLAN.md) and [architecture](docs/ARCHITECTURE.md) for the build sequence and system boundaries.

## Run with Docker

Install Docker Desktop or Docker Engine with the Compose plugin. Docker is the only runtime prerequisite on a new machine. The first build needs internet access to download images and dependencies. Java, Maven, Node.js, npm and SQLite are supplied by the images or the application.

If you have an existing local database, follow **Move an existing database into Docker** before first startup. Otherwise, run this from the repository root:

```sh
docker compose up -d --build
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). Nginx serves the frontend and forwards same-origin API requests to the private backend. The frontend starts after the backend health check passes. The port requires your native backend to be stopped.

| Task | Command |
| --- | --- |
| Check service state | `docker compose ps` |
| Follow logs | `docker compose logs -f backend frontend` |
| Stop both services | `docker compose stop` |
| Restart both services | `docker compose restart` |
| Check the API through Nginx | `curl -fsS http://127.0.0.1:8080/api/health` |

SQLite lives at `/data/tracker.sqlite` in the `tracker-data` named volume. Settings backups are stored in the separate `tracker-backups` volume at `/backups`. Both volumes survive container recreation. Keep the same Compose project name to reconnect to them. Avoid volume deletion commands. Set `TRACKER_TIME_ZONE` in an optional root `.env` to match your native tracker dates. Containers use their system time zone when it is omitted.

For backups, use **Settings → SQLite backups**. To restore one, enter `RESTORE` in the app and run `docker compose restart backend` to apply the queued restore. The application creates a pre-restore backup in the same volume. To keep a copy outside Docker, create a backup in Settings, then run `docker compose -f /absolute/path/to/compose.yaml cp backend:/backups ./tracker-backups-copy`. Choose a destination outside the checkout. A copied SQLite snapshot can also serve as the source for the migration procedure on a new machine.

If startup fails, check `docker compose ps` and backend logs first. For a port conflict, stop the native backend or other service using port 8080. If the browser shows a gateway error immediately after backend recreation, wait for the backend health check to pass and reload.

### Move an existing database into Docker

1. Stop the native backend and close other applications using its SQLite file. Keep the original file and its sidecars together. The native launcher defaults to `backend/data/tracker.sqlite`. If you set `TRACKER_DB_PATH`, use that file instead. Relative values resolve from the native backend directory.
2. From the repository root, build the backend and run the migration before starting the stack.

   ```sh
   docker compose stop
   docker compose build backend
   ./scripts/migrate-docker-db.sh "$PWD/backend/data/tracker.sqlite"
   docker compose up -d --build
   ```

3. Open the tracker and check your problems, progress and plans. Keep the original as a recovery copy. Docker and native runs use independent databases after the copy.

The migration opens the source through a read-only mount, copies committed data using SQLite, checks integrity and foreign keys, and installs the snapshot only if Docker storage has no existing database. Existing Docker data requires a deliberate backup and restore decision if the script refuses to proceed. Native backup directories stay on the host.

## Native development prerequisites

- JDK 21 and Maven 3.9 or later. Confirm that `mvn -version` reports Java 21.
- Node.js 24 LTS and npm 11. Confirm with `node --version` and `npm --version`.
- Available local ports 8080 for Spring Boot and 5173 for Vite.
- Internet access on the first dependency install. SQLite is bundled through its JDBC driver and requires no separate database server.

## Project-local setup

If Maven or a compatible Node.js version is missing from your shell, run this once from the repository root:

```sh
./scripts/setup-local.sh
```

Setup uses an existing npm command to bootstrap Node.js 24 and npm 11 and installs Maven 3.9.16. It reuses a checksum-verified Maven archive when one is cached locally. The launchers then use executable tools from `.tools/`. Maven libraries go in `.tools/maven-repository/` and frontend dependencies go in `frontend/node_modules/`. The macOS backend launcher can locate an installed JDK 21 when `JAVA_HOME` is unset.

For optional settings, copy `.env.example` to `.env` and edit the values you need. The backend launcher reads the database path, time zone, and backup root from that file. Exported environment variables take precedence. Default settings work without creating the file.

## Run in development

From the repository root, start the backend in one terminal.

```sh
./scripts/dev-backend.sh
```

Start the frontend in a second terminal.

```sh
./scripts/dev-frontend.sh
```

The frontend script installs dependencies from the lockfile when they are missing. Run `npm ci` in `frontend/` after a lockfile change. You can also run `mvn spring-boot:run` in `backend/` and `npm run dev` in `frontend/`.

Open [http://127.0.0.1:5173](http://127.0.0.1:5173). Both development servers bind to loopback. Vite sends API requests to the local backend at port 8080. The backend accepts localhost hosts and direct cross-origin requests from the two local Vite origins on port 5173.

Use `curl -i http://127.0.0.1:8080/api/health` to check the backend. The health endpoint returns HTTP 200 with `{"status":"UP"}` when SQLite responds or HTTP 503 with `{"status":"DOWN"}` when it cannot connect.

Press **Ctrl+C** in each terminal to stop the servers. For a queued database restore, stop the backend, close other SQLite applications, and start the backend again with the same settings. The frontend can remain running while the backend restarts.

## Local database

The commands above create `backend/data/tracker.sqlite` on first startup. The same file is reused across runs. Flyway applies ordered SQL migrations from `backend/src/main/resources/db/migration/` and records them in the database. V1 establishes the migration baseline. V2 adds the catalog, topic links, and one progress record per problem. V3 adds goals and scheduled problems. V4 adds revision tasks. Spring Data JPA is configured with the Hibernate community SQLite dialect. Flyway owns schema changes.

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

## Revision queue

Open **Revisions** for due today, overdue, upcoming, completed, and cancelled tasks. Only solved problems can be scheduled. The first transition to Solved schedules a review seven calendar days after the solved date. Correcting the solved date moves that initial review while it remains automatic. Completing a revision schedules another for seven days after the local completion date. Reopening a problem cancels scheduled revisions and preserves completed ones. Problems already solved when upgrading can be scheduled manually.

- `GET /api/revisions?filter=DUE_TODAY|OVERDUE|UPCOMING|COMPLETED|CANCELLED` returns queue items and due counts.
- `POST /api/revisions` schedules a solved problem with `problemId` and `date`.
- `GET /api/revisions/{id}` reads a task, and `PUT /api/revisions/{id}` reschedules it with `date`.
- `POST /api/revisions/{id}/complete` and `POST /api/revisions/{id}/cancel` close a scheduled task. Completion preserves the problem’s original solved date.

## Catalog import and export

Open **Settings** to export CSV or JSON, preview an import, and apply valid rows. Catalog files include problem metadata, topics and current progress. SQLite backups capture the entire database, including planner, goals, and revision history.

- CSV requires UTF-8 and headers `leetcodeId,title,url,difficulty,topics`. Optional headers are `status,attemptedDate,solvedDate,comments`. Use semicolons between topics. Escape a literal semicolon as `\;` and a literal backslash as `\\`. Standard CSV quoting preserves commas, quotes and multiline comments.
- JSON uses `{"version":1,"problems":[...]}` with the same fields and an array of strings for `topics`. Use the catalog difficulty and status names and ISO calendar dates.
- Both modes match by LeetCode ID. `SKIP_EXISTING` leaves existing problems alone. `UPDATE_EXISTING` replaces their catalog fields and topics while preserving status, dates and comments. New problems can include imported progress. All rows with a repeated ID within the file are reported as invalid.
- Preview is read-only. Apply revalidates the file and commits each valid row in its own transaction. Invalid or failed rows are reported individually. The file limit is 10 MB and 10,000 rows.
- `POST /api/exchange/preview` and `POST /api/exchange/import` accept a multipart `file` and query parameters `format=CSV|JSON` and `mode=SKIP_EXISTING|UPDATE_EXISTING`. `GET /api/exchange/export?format=CSV|JSON` downloads the catalog.

## SQLite backup and restore

**Settings → SQLite backups** creates an online snapshot of committed data. The default root is `~/.dsa-tracker/backups`. Set `TRACKER_BACKUP_ROOT` to another location outside any Git checkout. Backups for each configured database live in a separate subdirectory named by a hash of its canonical path. Filenames contain a UTC timestamp and a generated UUID. Protect this directory as you would the live database.

1. Select a backup, type `RESTORE`, and queue the restore. The app validates SQLite integrity, foreign keys, and Flyway history, then records a checksum of the confirmed file.
2. Stop the backend and close other SQLite clients. Restart the backend with the same database and backup settings. Startup saves a recovery backup of the latest live data, verifies the confirmed checksum and revalidates the replacement, then swaps it in atomically before opening the connection pool.
3. Reopen Settings. The pending marker clears after the swap. The recovery backup appears in the same list and can be queued if you need to return to the pre-restore state.

`GET /api/backups` lists backups and pending state. `POST /api/backups` creates a backup. `POST /api/backups/{id}/restore` accepts `{"confirmation":"RESTORE"}`. `DELETE /api/backups/restore` clears a pending restore. All milestone endpoints also support the `/api/v1` prefix.

### Manual backup and recovery

If using the SQLite CLI, run these commands from the backend directory and use the same `TRACKER_DB_PATH` as the app.

```sh
db_path="${TRACKER_DB_PATH:-data/tracker.sqlite}"
manual_root="$HOME/.dsa-tracker/manual-backups"
mkdir -p "$manual_root"
backup_path="$manual_root/tracker-$(date -u +%Y%m%dT%H%M%SZ).sqlite"
sqlite3 "$db_path" '.timeout 5000' ".backup \"$backup_path\""
sqlite3 -readonly "$backup_path" 'PRAGMA integrity_check; PRAGMA foreign_key_check;'
```

An intact backup prints `ok` and no foreign-key violations. For manual restoration, first stop the backend and every SQLite client. Use a verified backup and preserve the live database separately. If the live database opens, take another backup as shown above and checkpoint its WAL.

```sh
# backup_path must point to the verified snapshot you want to restore.
sqlite3 "$db_path" 'PRAGMA wal_checkpoint(TRUNCATE);'
staged_db=$(mktemp "$(dirname "$db_path")/.tracker-restore.XXXXXX")
cp "$backup_path" "$staged_db"
sqlite3 -readonly "$staged_db" 'PRAGMA integrity_check; PRAGMA foreign_key_check;'
```

Continue only after the checkpoint reports an unblocked result, the candidate passes integrity checks, and no `-wal`, `-shm`, or `-journal` sidecars remain. With the backend still stopped, run `mv -i "$staged_db" "$db_path"` and restart. If startup repeatedly fails while applying a queued restore, move the affected database’s `pending-restore` marker to a safe location before attempting manual recovery. A marker from a version that did not record a checksum also requires this step. Once the app starts with the existing database, select and confirm the intended backup again. If the live database cannot open, preserve it and its sidecars together in a separate recovery directory before installing any replacement. Keep the prior recovery copy until the restored tracker opens and you have checked your data.

## Verify and package

From the repository root, run the frontend checks and build before packaging the backend so Maven can include the built frontend in the JAR.

```sh
cd frontend
npm ci
npm test
npm run build

cd ../backend
mvn clean verify
cd ..
python3 scripts/smoke-test.py
```

The optional packaged smoke test needs Python 3.9 or later and Java on `PATH`. It runs the actual HTTP service on loopback with an ephemeral port and a temporary database. It checks catalog CRUD, goals, planner, analytics, revisions, file exchange, and a full restore across process restarts.

To run the packaged app from the repository root:

```sh
cd backend
java -jar target/dsa-tracker-0.1.0-SNAPSHOT.jar
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). Press **Ctrl+C** in the terminal to stop it. Navigation and health checks use local assets and same-origin requests.
