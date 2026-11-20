# DSA Tracker

A local-first workspace for data structures and algorithms practice. Milestone 1 provides the application shell, local SQLite setup, and a Dashboard connection check. The other navigation destinations are placeholders.

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

The commands above create `backend/data/tracker.sqlite` on first startup. The same file is reused across runs. Flyway applies ordered SQL migrations from `backend/src/main/resources/db/migration/` and records them in the database. The first migration establishes the migration baseline. Spring Data JPA is configured with the Hibernate community SQLite dialect. Flyway owns schema changes.

- Set `TRACKER_DB_PATH` to use a different database file. Relative paths resolve from the backend process working directory.
- Set `TRACKER_TIME_ZONE` to an IANA time zone such as `Asia/Kolkata`. The default is the host time zone.
- Keep local database and environment files out of source control.

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
