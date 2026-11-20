# DSA Tracker development instructions

DSA Tracker is a local-first, single-user application for data structures and algorithms practice. Use Java 21, Spring Boot, Maven, React, TypeScript, Tailwind CSS, and SQLite.

## Before changing the project

- Read the [implementation plan](docs/IMPLEMENTATION_PLAN.md) and [architecture](docs/ARCHITECTURE.md), including their diagrams. Follow the documented phase order, behavior, and system boundaries.
- Wait for plan approval before starting application implementation. Work in small, independently testable milestones and complete the current milestone's verification before opening the next one.
- Inspect the working tree before editing. Preserve existing code and documentation unless the requested milestone requires a change. Flag a conflict with the existing plan or architecture before implementing the conflicting behavior.

## Backend and frontend

- Build a modular Spring Boot monolith. Organize backend packages by feature and keep controller, service, repository, and DTO responsibilities distinct.
- Expose versioned REST endpoints under `/api/v1`. Controllers handle HTTP, map request and response DTOs, validate request shape, and delegate to services. Return structured errors that identify invalid fields or import rows where applicable.
- Put business rules, workflows, and transaction boundaries in application services. Keep service inputs and results independent of HTTP so a future MCP adapter can reuse the services.
- Repositories own Spring JDBC queries and persistence mapping. Use parameterized SQL. Keep the frontend on the REST API and route durable changes through backend services.
- Keep SQLite authoritative. Bind the backend to loopback by default, use a configurable database path and application time zone, and bundle frontend assets for offline use. Use `LocalDate` for date-only values and inject a clock where business rules depend on today.

## Data and V1 scope

- Keep the problem catalog separate from progress, planner entries, goals, and revision schedules. Give each problem a unique LeetCode ID and support multiple topics through a problem-topic junction table.
- Store one current progress record per problem. Keep status, attempted date, solved date, and comments together. Do not add attempt-history tables or attempt-history functionality.
- Use ordered, versioned SQLite-compatible SQL migrations. Flyway is an option if the selected setup supports SQLite. Verify migrations on a fresh database and on an existing database whenever the schema changes.
- Enable SQLite foreign keys on every connection. Use database constraints for enforceable invariants and transactions for changes that must succeed together, including progress and revision updates and confirmed full restores.
- Keep MCP server implementation, AI integration, and automatic LeetCode synchronization outside V1.
- Keep secrets, local SQLite database files, and SQLite sidecar files out of Git. Verify ignore rules when adding local database configuration.

## Verification and handoff

- Add focused tests for business rules, validation failures, and persistence invariants introduced or changed by the milestone. Use a temporary SQLite database for integration tests.
- Run the relevant backend tests and Maven build and the relevant frontend tests and build after each milestone. Check migrations or offline operation when a change affects them.
- Summarize every change, the tests and builds run with their results, and any issues or checks that remain.
