# MCP integration

The existing Java 21 Spring Boot application serves REST and can opt in to a local MCP endpoint. The server exposes `ping_tracker`, three read-only catalog tools, five read-only reporting tools, six read-only study tools, and three read-only LeetCode discovery tools. Tool results include structured JSON. Catalog tools read the local SQLite catalog through the same application services used by REST.

## Dependencies and transport

Spring AI **2.0.3** is in the 2.0 release line that supports Spring Boot **4.1.x**. The project keeps its Spring Boot **4.1.3** parent, imports `org.springframework.ai:spring-ai-bom:2.0.3`, and uses `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` to match the existing servlet stack. The starter supplies MCP Java SDK **2.1.0** and registers the annotated tools. [Spring AI compatibility](https://github.com/spring-projects/spring-ai), [Spring AI 2.0.3 release](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/).

```properties
server.address=127.0.0.1
spring.ai.mcp.server.enabled=${TRACKER_MCP_ENABLED:false}
spring.ai.mcp.server.name=dsa-tracker
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp
spring.ai.mcp.server.tool-callback-converter=false
spring.ai.mcp.server.capabilities.resource=true
spring.ai.mcp.server.capabilities.prompt=true
spring.ai.mcp.server.capabilities.completion=false
```

## Start the backend

From the repository root, run the native backend with Java 21 and the project's Maven launcher:

```sh
TRACKER_MCP_ENABLED=true ./scripts/dev-backend.sh
curl -fsS http://127.0.0.1:8080/api/health
```

Connect to `http://127.0.0.1:8080/mcp`. If Docker or another service already uses port 8080, add `SERVER_PORT=18081` to the startup command and use that port for both URLs. If another tracker backend already has your SQLite database open, stop it or set `TRACKER_DB_PATH` to a separate absolute path before starting. To disable MCP, restart with `TRACKER_MCP_ENABLED=false` or omit the variable.

## Start with Docker Compose

From the repository root, enable MCP and rebuild the application images:

```sh
TRACKER_MCP_ENABLED=true docker compose up -d --build
```

If you use the original SQLite file through the database bind-mount override, keep the override in the command:

```sh
TRACKER_MCP_ENABLED=true docker compose -f compose.yaml -f compose.bind-db.yaml up -d --build
```

The browser and Inspector share host port 8080. Connect Inspector using Streamable HTTP at `http://127.0.0.1:8080/mcp`. Nginx shares the backend network namespace, reaches Spring Boot over loopback, and forwards MCP sessions without buffering. Docker publishes only the Nginx port on host loopback.

Set `TRACKER_MCP_ENABLED=true` in the root `.env` to keep MCP enabled between launches. Set it to `false` and run the same Compose command with that value to disable it. Use `up` to apply the changed environment.

## Test with MCP Inspector

In a second terminal, run the official Inspector with a supported Node.js version. Inspector 2.16.0 was verified with Node.js 24.22.3.

```sh
npx -y @modelcontextprotocol/inspector@2.16.0 --cli http://127.0.0.1:8080/mcp --transport http --method tools/list
npx -y @modelcontextprotocol/inspector@2.16.0 --cli http://127.0.0.1:8080/mcp --transport http --method tools/call --tool-name ping_tracker
```

To use the web UI, run `npx -y @modelcontextprotocol/inspector@2.16.0 --web --server-url http://127.0.0.1:8080/mcp --transport http`. Connect using **Streamable HTTP**, open **Tools**, list tools, and run `ping_tracker` with no arguments. Use the Inspector's local proxy. [MCP Inspector documentation](https://github.com/modelcontextprotocol/inspector).

Expected structured result:

```json
{
  "status": "UP",
  "application": "DSA Tracker",
  "message": "MCP server connected successfully"
}
```

## Milestone 2 · Catalog tools

Open the Inspector **Tools** tab, select **List Tools**, and confirm that `ping_tracker`, `search_problems`, `get_problem`, and `list_topics` appear. Select a catalog tool, enter the example arguments below, and check **structuredContent** in the result. Use LeetCode IDs and topics that exist in the database you started.

For the CLI, set the endpoint to the port used by your backend. These examples assume LeetCode ID 1 and the Array topic are in the catalog.

```sh
endpoint=http://127.0.0.1:8080/mcp
npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http --method tools/call --tool-name search_problems --tool-arg query=sum
npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http --method tools/call --tool-name get_problem --tool-arg leetcodeId=1
npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http --method tools/call --tool-name list_topics --tool-arg query=aRRay
```

### search_problems

Searches the local catalog by title, LeetCode ID, topics, difficulty, or current status. All arguments are optional and combine with AND. Topic names within a single filter combine with OR.

- `query` is a case-insensitive title substring or LeetCode ID search, up to 255 characters.
- `leetcodeId` is a positive integer and requires an exact match.
- `topicNames` is an array of up to 100 nonblank strings, each at most 80 characters after trimming. Names match exactly after case normalization. An omitted or empty list disables topic filtering. Unknown names cannot produce unrelated results. If every supplied name is unknown, the result is empty.
- `difficulty` is `EASY`, `MEDIUM`, or `HARD`.
- `status` is `NOT_STARTED`, `ATTEMPTED`, or `SOLVED`.
- `page` is a zero-based integer and defaults to 0. `size` is an integer from 1 to 100 and defaults to 20. Results sort by LeetCode ID ascending.

Example arguments:

```json
{"query":"subarray","topicNames":["Array","Prefix Sum"],"difficulty":"HARD","size":20}
```

Example result. Each item includes numeric IDs, strings for title and URL, difficulty and status enums, a topic name array, and nullable ISO dates.

```json
{
  "items": [{
    "id": 125, "leetcodeId": 862, "title": "Shortest Subarray with Sum at Least K",
    "url": "https://leetcode.com/problems/shortest-subarray-with-sum-at-least-k/",
    "difficulty": "HARD", "topics": ["Array", "Prefix Sum"], "status": "NOT_STARTED",
    "attemptedDate": null, "solvedDate": null
  }],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1
}
```

### get_problem

Retrieves full local problem details and current progress by a required positive integer `leetcodeId`. An ID absent from the catalog returns an MCP tool error naming the requested ID.

Example arguments: `{"leetcodeId":862}`.

Example result:

```json
{
  "id": 125, "leetcodeId": 862, "title": "Shortest Subarray with Sum at Least K",
  "slug": "shortest-subarray-with-sum-at-least-k",
  "url": "https://leetcode.com/problems/shortest-subarray-with-sum-at-least-k/",
  "difficulty": "HARD", "topics": ["Array", "Prefix Sum"],
  "progress": {"status":"NOT_STARTED","attemptedDate":null,"solvedDate":null,"comments":""}
}
```

### list_topics

Lists topic IDs and names ordered by normalized name. Optional string `query` is a case-insensitive substring filter, up to 80 characters after trimming. Omit it or pass blank text to list all topics.

Example arguments: `{"query":"aRRay"}`.

Example result: `{"topics":[{"id":1,"name":"Array"}]}`.

Catalog tools only see records already in the local database. Search results are paginated, while the topic list is unpaginated. Search matches title substrings as supported by SQLite. Topic names use the catalog's Unicode normalization and case-insensitive comparison key.

## Milestone 3 · Analytics and progress tools

In Inspector, connect to the configured local `/mcp` endpoint with **Streamable HTTP** and select **Tools → List Tools**. Select a reporting tool, enter example arguments, and read the result in **structuredContent**. The same calls are available in Inspector CLI:

```sh
endpoint=http://127.0.0.1:8080/mcp
inspector() { npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http "$@"; }
inspector --method tools/call --tool-name get_dashboard_summary
inspector --method tools/call --tool-name get_topic_progress --tool-args-json '{"range":"7"}'
inspector --method tools/call --tool-name get_difficulty_progress --tool-args-json '{"range":"all"}'
inspector --method tools/call --tool-name get_solving_activity --tool-args-json '{"range":"30"}'
inspector --method tools/call --tool-name get_recent_problems --tool-args-json '{"limit":5}'
```

Pass ranges as strings. The accepted values are `"7"`, `"30"`, `"90"`, and `"all"`. A bounded range includes today and the preceding calendar days in the tracker timezone configured by `TRACKER_TIME_ZONE`. The existing reporting service determines date boundaries and counts from saved progress. Invalid ranges and limits return tool errors.

### get_dashboard_summary

Takes no arguments. The integer counts `totalProblems`, `solved`, `attempted`, and `notStarted` reflect current status. Every problem counts once. `solvedPercentage` is a number from 0 to 100 and is zero for an empty catalog.

### get_topic_progress

Optional `range` defaults to `"all"`. Returns `{range, topics}`, with one entry per topic. Each entry includes numeric `topicId`, `topicName`, and integer counts `totalProblems`, `solvedProblems`, `unsolvedProblems`, and `solvedProblemsAllTime`. `solvedProblems` uses the selected range. `solvedPercentage` divides that range count by the full topic size and is zero for an empty topic. `unsolvedProblems` and `solvedProblemsAllTime` reflect current status across all time. A problem tagged with multiple topics can appear in each topic's counts. Use the dashboard summary for global totals.

### get_difficulty_progress

Optional `range` defaults to `"all"`. Returns `{range, difficulties}`, with `{difficulty, solvedCount}` for each of `EASY`, `MEDIUM`, and `HARD` in that order. A missing category has a zero solved count. The underlying difficulty report supplies solved counts only.

### get_solving_activity

Optional `range` defaults to `"30"`. Returns `{range, startDate, endDate, days, totalSolvesInRange}`. Each day has an ISO calendar `date` and integer `solvedCount`. The total sums the returned day counts. Bounded ranges with activity include zero-count days. Bounded ranges without activity return an empty day list. For `"all"`, `startDate` is null and only dates with solves appear.

### get_recent_problems

Optional integer `limit` defaults to 10 and accepts 1 through 50 per list. Returns `{recentlySolved, recentlyModified}` in the order supplied by the existing recent report. Each entry includes numeric `id` and `leetcodeId`, `title`, current `status`, and `solvedDate` as an ISO date or null.

### Counting limits

An undated problem with saved status `SOLVED` counts in the dashboard and all-time topic and difficulty totals. Bounded solve counts, solving activity, and recently solved entries require a saved solved date. Editing a saved status or date can change past activity because the database stores current progress. The tools provide no attempt history.

## Milestone 4 · Planner, goals, and revisions

The study tools read the same SQLite records as the web app. Date arguments must be ISO calendar dates in `YYYY-MM-DD` format with years 0001 through 9999. Missing dates resolve to today in the zone configured by `TRACKER_TIME_ZONE`, or the application runtime zone when unset. Planner days, goal periods, and revision dates are calendar dates. The latest review timestamp is converted to a date in the tracker zone.

In Inspector, connect over **Streamable HTTP**, open **Tools → List Tools**, and select a tool. Inspect **structuredContent** after submitting the arguments. CLI examples:

```sh
endpoint=http://127.0.0.1:8080/mcp
inspector() { npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http "$@"; }
inspector --method tools/call --tool-name get_daily_plan
inspector --method tools/call --tool-name get_weekly_plan --tool-args-json '{"date":"2026-11-20"}'
inspector --method tools/call --tool-name list_goals --tool-args-json '{"activeOnly":true}'
inspector --method tools/call --tool-name get_goal_progress --tool-args-json '{"goalId":1}'
inspector --method tools/call --tool-name get_due_revisions --tool-args-json '{"limit":10}'
inspector --method tools/call --tool-name get_upcoming_revisions --tool-args-json '{"days":14,"limit":20}'
```

Use an existing `goalId` for the goal details example. You can find one with `list_goals`.

### get_daily_plan

Optional string `date`. The result has `date`, integer `totalScheduled`, `completedCount`, and `skippedCount`, and a `scheduledProblems` array. Each entry includes integer `plannerEntryId`, `problemId`, and `leetcodeId`, string `title`, `difficulty`, `progressStatus`, ISO `scheduledDate`, and boolean `completed` and `skipped`. The total includes skipped entries. The existing planner marks a non-skipped entry complete only when its problem's current status is SOLVED and its saved solved date matches the scheduled date. Empty days return zero counts and an empty array.

### get_weekly_plan

Optional string `date` selects its Monday–Sunday week. The result includes `startDate`, `endDate`, the same three aggregate counts, and `days`, an array of seven daily plan objects, including empty days.

### list_goals

Optional boolean `activeOnly` defaults to false. When true, the list includes goals whose inclusive period contains tracker today, including completed goals still in that period. Returns `{goals}`, an array of objects with `goalId`, `name`, `type`, `startDate`, `endDate`, integer `targetCount` and `currentCount`, numeric `completionPercentage`, and nullable `topicId`. No matching goals gives an empty array.

### get_goal_progress

Required positive integer `goalId`. The result includes `goalId`, `name`, `type`, `startDate`, `endDate`, integer `targetCount`, `solvedCount`, and `remainingCount`, numeric `completionPercentage`, boolean `complete`, and nullable `topicId` and `topicName`. Percentages are capped at 100. Existing goal criteria count distinct currently solved problems using saved solve dates and current topic membership. Unknown IDs return a tool error.

### get_due_revisions

Optional string `date` sets the inclusive cutoff. Optional integer `limit` defaults to 20 and accepts 1–100. Returns `{date, revisions}`. Each item includes `revisionId`, `problemId`, `leetcodeId`, `title`, `difficulty`, ISO `nextRevisionDate`, and boolean `overdue`, which is true for dates strictly before the requested cutoff.

### get_upcoming_revisions

Optional string `fromDate` starts the window. Optional integer `days` defaults to 7 and accepts 1–90, including the first date. Optional integer `limit` defaults to 20 and accepts 1–100. Returns `{fromDate, endDate, revisions}`. Entries have the same identifying fields and `nextRevisionDate` as the due list, plus nullable ISO `latestReviewedDate` from the latest recorded completion for that problem.

Both revision tools use the implemented revision queue. They include scheduled rows only, sorted by scheduled date and then revision ID, with the limit applied in SQLite. A problem with several scheduled reviews can appear more than once. A review on the selected date can appear in both the due and upcoming results. Empty windows return an empty revisions array. The tools do not infer missing schedules or review dates.

## Milestone 5A · Opt-in progress and planner writes

MCP write tools require both `TRACKER_MCP_ENABLED=true` and `TRACKER_MCP_WRITES_ENABLED=true`. Writes default to false and the read tools remain available when only MCP is enabled. The existing loopback, Host, and Origin checks apply to writes. Clients should show the target and proposed change and obtain explicit user approval before calling a write tool. Approval is a client responsibility. Turning on the server flag permits local clients to call the tools.

Native backend:

```sh
TRACKER_MCP_ENABLED=true TRACKER_MCP_WRITES_ENABLED=true ./scripts/dev-backend.sh
```

Docker Compose with the original database bind mount:

```sh
TRACKER_MCP_ENABLED=true TRACKER_MCP_WRITES_ENABLED=true \
  docker compose -f compose.yaml -f compose.bind-db.yaml up -d --build
```

For the standard Docker volume, use `docker compose up -d --build` with the same variables. Set `TRACKER_MCP_WRITES_ENABLED=false` and restart the native backend or run Compose `up` again to disable writes.

### update_problem_progress

Required `leetcodeId` (positive integer) and `status` (`NOT_STARTED`, `ATTEMPTED`, or `SOLVED`). Optional `attemptedDate` and `solvedDate` accept an ISO calendar date (`YYYY-MM-DD`) or JSON null. Omission preserves the existing date where applicable or uses the existing status transition defaults in the tracker timezone. An explicit null clears an optional date or is rejected if the chosen status requires a date. Optional `comments` accepts up to 10,000 characters. Omit it to preserve comments or send an empty string to clear them. JSON null comments are rejected.

Returns `leetcodeId`, `status`, nullable `attemptedDate` and `solvedDate`, `comments`, and `updatedAt`. The existing progress service handles associated revision reconciliation in the same transaction.

### schedule_problem

Required positive integer `leetcodeId` and ISO string `date`. An unknown problem or an existing schedule for that problem and date returns an MCP tool error. Returns the created entry using the same fields as an item in `get_daily_plan`.

### reschedule_problem

Required positive integer `plannerEntryId` and ISO string `date`. The existing planner rules prevent moves of completed tasks and duplicate schedules. Skipped entries remain skipped when moved. Returns the updated entry in the same shape as `schedule_problem`.

### Inspector examples

Connect over Streamable HTTP to the local `/mcp` endpoint, then use **Tools → List Tools** to verify that the three write tools appear only when enabled. The examples below change data. Use a test database or approve the target and changes before running them.

```sh
endpoint=http://127.0.0.1:8080/mcp
inspector() { npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http "$@"; }
inspector --method tools/call --tool-name update_problem_progress --tool-args-json '{"leetcodeId":862,"status":"SOLVED","attemptedDate":null}'
inspector --method tools/call --tool-name schedule_problem --tool-args-json '{"leetcodeId":862,"date":"2026-11-27"}'
inspector --method tools/call --tool-name reschedule_problem --tool-args-json '{"plannerEntryId":1,"date":"2026-11-28"}'
```

Use the `plannerEntryId` returned by scheduling for the reschedule example. Milestone 5B adds separate goal and revision tools below.

## Milestone 5B · Opt-in goal and revision writes

These tools use the same `TRACKER_MCP_ENABLED=true` and `TRACKER_MCP_WRITES_ENABLED=true` switches as the other write tools. Clients should show the proposed change and obtain explicit user approval before each call.

Flyway migration `V5__goal_difficulty.sql` adds a nullable difficulty restriction to goals. Existing goals retain their behavior. Goal REST responses and MCP goal reads include `difficulty`. REST POST and PUT accept `EASY`, `MEDIUM`, `HARD`, or null. On a REST PUT, omitting difficulty preserves the existing restriction. Explicit null clears it.

Goal progress counts distinct problems whose current status is SOLVED and whose saved solved dates are inside the inclusive goal period. If a topic or difficulty restriction is present, the problem must match it. When both are set, the problem must match both.

### create_goal

Required inputs are string `name`, `type` (`DAILY`, `WEEKLY`, or `CUSTOM`), ISO `startDate`, and positive integer `targetCount`. Optional inputs are ISO `endDate` (required for CUSTOM), string `topicName`, and `difficulty` (`EASY`, `MEDIUM`, or `HARD`). Topics are matched case-insensitively against existing names. Unknown names are rejected. Weekly periods start Monday. Daily and weekly end dates are derived if omitted.

Returns the same fields as `get_goal_progress`, including `goalId`, period dates, progress counts and percentage, and nullable topic and difficulty restrictions. Creating a goal leaves the planner untouched.

### update_goal

Required positive integer `goalId`. Optional inputs are `name`, `targetCount`, `topicName`, `difficulty`, `startDate`, and `endDate`. Omitted fields retain their values, with the daily or weekly end date recalculated when its start changes. Use JSON null for `topicName` or `difficulty` to clear that restriction. Other fields reject null. The goal type stays fixed. Returns the updated goal in the same shape as `create_goal`.

### complete_revision

Required positive integer `leetcodeId`. Optional ISO `reviewedDate` defaults to today in the tracker timezone and cannot be in the future. The service completes the earliest scheduled revision for the problem, breaking ties by revision ID. The existing seven-day rule schedules the follow-up from the review date. A historical review is recorded at the start of that date in the tracker timezone. Problem status and saved solve dates stay intact.

The response contains `revisionId`, `problemId`, `leetcodeId`, `title`, `scheduledDate`, `status`, `completedAt`, and `nextRevisionDate`. Unknown problems and problems with no scheduled revision return a tool error. Completion changes which revision is earliest. Repeating the call may complete another scheduled revision, so clients should avoid automatic retries.

### Inspector examples

With an approved target or a temporary database, connect over Streamable HTTP and call:

```sh
endpoint=http://127.0.0.1:8080/mcp
inspector() { npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http "$@"; }
inspector --method tools/call --tool-name create_goal --tool-args-json '{"name":"Hard DP","type":"CUSTOM","startDate":"2026-11-26","endDate":"2026-12-02","targetCount":10,"topicName":"Dynamic Programming","difficulty":"HARD"}'
inspector --method tools/call --tool-name update_goal --tool-args-json '{"goalId":1,"targetCount":12,"difficulty":null}'
inspector --method tools/call --tool-name complete_revision --tool-args-json '{"leetcodeId":862,"reviewedDate":"2026-11-26"}'
```

Use the goal ID returned by creation and a problem that has a scheduled revision.

## Milestone 6A · LeetCode discovery

Discovery tools are available when MCP is enabled, including when writes are disabled. They make on-demand, unauthenticated requests to the unofficial `POST https://leetcode.com/graphql/` endpoint. The `problemsetQuestionList` operation reads `questionList` with `limit` and `skip`. The tools fetch public problem metadata and compare IDs with the local SQLite catalog. Preview IDs identify temporary in-memory snapshots. Milestone 6B can import selected candidates from a valid snapshot when writes are enabled.

### search_leetcode_problems

All inputs are optional. `query` is a case-insensitive title substring (up to 255 characters). `topicNames` accepts up to 100 exact topic names (up to 80 characters each), ignores case and surrounding whitespace, and matches any listed topic. `difficulty` is `EASY`, `MEDIUM`, or `HARD`. `includePremium` defaults to false. `limit` defaults to 20 and accepts 1–100. `page` defaults to zero.

Returns `{items, page, hasMore, stopReason}`. Each item has `leetcodeId`, `title`, `slug`, `url`, `difficulty`, `topics`, `paidOnly`, and `alreadyImported`. The scanner combines title, topic, difficulty, and premium filters before applying the local page offset and limit. Matching items retain remote order. `hasMore` means the scan found at least one further matching item. When a budget is reached, `hasMore=false` can mean no further item was verified.

### get_leetcode_problem

Requires a positive numeric `leetcodeId`. Returns the same fields as a search item. The tool checks its metadata cache first, then scans pages by numeric ID. A completed catalog scan with no match returns a not-found tool error. A scan stopped by its budget reports that the ID could not be verified. Local membership is checked each time, including on cache hits. This lookup can return premium metadata.

### preview_leetcode_import

All inputs are optional. `count` defaults to 100 and accepts 1–500. `topicNames`, `difficulty`, and `includePremium` follow the search rules. The preview excludes local IDs, duplicate remote IDs, and invalid metadata. It returns `previewId`, `requestedCount`, `returnedCount`, `scannedCount`, `skippedExisting`, `skippedInvalid`, `candidates`, `expirationTime`, and `stopReason`. Counts describe rows examined before stopping. Existing IDs are counted once even if they appear in more than one remote page. Candidates use the search item schema.

### Limits and errors

- Scans request up to 100 rows per remote page and default to 50 pages and a 14-second scan budget. `RESULT_LIMIT` means the requested results or search lookahead were found. `CATALOG_EXHAUSTED` means the reported end or an empty remote page was reached. `PAGE_LIMIT` and `TIME_LIMIT` identify partial scans stopped by their respective budgets.
- Connection and read timeouts default to 2 and 3 seconds. Transient I/O errors and HTTP 5xx responses permit at most one retry per page, within the scan budget. Only one remote HTTP request can be in flight. HTTP 429 applies a shared cooldown based on `Retry-After`, or 30 seconds when unavailable. GraphQL errors, incompatible payloads, and failed requests return tool errors.
- Configure Spring properties `tracker.leetcode.connect-timeout`, `tracker.leetcode.read-timeout`, `tracker.leetcode.scan-timeout`, `tracker.leetcode.max-pages`, and `tracker.leetcode.preview-ttl`. The scan time must exceed the read timeout and is capped at 18 seconds. Preview storage holds up to 16 snapshots and defaults to a 10-minute expiration. Metadata lookup holds up to 10,000 entries for up to 10 minutes. Both caches are process-local.
- The public endpoint can change, rate-limit callers, or fail. Results may differ between scans as LeetCode changes its ordering or catalog. Discovery needs network access. Existing local tracker tools continue to work offline. Only positive numeric frontend IDs, valid metadata, and catalog-compatible slugs are mapped.

### Inspector examples

With MCP running, connect Inspector via Streamable HTTP and select **Tools → List Tools**. To call from the CLI:

```sh
endpoint=http://127.0.0.1:8080/mcp
inspector() { npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http "$@"; }
inspector --method tools/call --tool-name search_leetcode_problems --tool-args-json '{"query":"array","difficulty":"EASY","limit":10}'
inspector --method tools/call --tool-name get_leetcode_problem --tool-args-json '{"leetcodeId":4}'
inspector --method tools/call --tool-name preview_leetcode_import --tool-args-json '{"count":25,"topicNames":["Dynamic Programming"]}'
```

Read `structuredContent` and check `stopReason` on search and preview results when fewer items are returned.

## Milestone 6B · Import selected LeetCode problems

`import_leetcode_problems` is available when both `TRACKER_MCP_ENABLED=true` and `TRACKER_MCP_WRITES_ENABLED=true` are set. The existing loopback protections still apply. The MCP client should show the candidate selection and obtain explicit user approval before calling the tool. LeetCode imports use client-side approval. The separate operator workflow below applies to study-plan commits.

The input is a `previewId` UUID from `preview_leetcode_import` and `leetcodeIds`, an array of 1–500 positive integer IDs. Every ID must be a candidate in that preview. Duplicate selections are processed once. The server uses its stored metadata and makes no GraphQL calls during import.

```json
{"previewId":"8e171a29-cb18-4bde-bac5-c5625136fcc8","leetcodeIds":[4,5,10]}
```

Output contains integer `imported`, `skipped`, and `failed` counts, plus `failures` entries with `leetcodeId` and `message`. Invalid input or a missing, expired, evicted, or pre-restart preview fails the entire request before writes. A storage or metadata failure for a valid candidate is reported per problem, and the remaining selections can still import.

Each candidate uses its own SQLite transaction. Existing problem IDs are rechecked inside the transaction and skipped with their current metadata and progress intact. New problems reuse topics by normalized name, create missing topics, and start with `NOT_STARTED` progress. Candidates with no topics or more than 100 topics fail the catalog's import requirements. Repeating a valid preview selection skips completed imports, and concurrent calls share SQLite's uniqueness and writer locking rules.

### Inspector example

Use an approved selection or a temporary database. Start the native backend with both switches enabled, or pass both to Docker Compose. Connect Inspector over **Streamable HTTP**, call the preview tool, and copy its UUID and the IDs you want from `candidates`. In Inspector's **Tools** view, call `import_leetcode_problems` with that selection. CLI equivalent:

```sh
endpoint=http://127.0.0.1:8080/mcp
npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http \
  --method tools/call --tool-name import_leetcode_problems \
  --tool-args-json '{"previewId":"8e171a29-cb18-4bde-bac5-c5625136fcc8","leetcodeIds":[4,5,10]}'
```

Replace the sample UUID and IDs with those from your preview.

## Milestone 7A · Study plan recommendations

`recommend_study_plan` is available when MCP is enabled, including with writes disabled. It uses the local catalog and planner and makes no external requests.

### Inputs

- `topicNames` is optional. Names are trimmed, normalized, and matched case-insensitively through the catalog. Multiple known names match with OR semantics. If all supplied names are unknown, there are no eligible problems. Omit the list or pass an empty list for any topic.
- `difficulty` is optional and accepts `EASY`, `MEDIUM`, or `HARD`.
- `startDate` accepts an ISO `YYYY-MM-DD` date and defaults to today in `TRACKER_TIME_ZONE`, or the application runtime zone when unset.
- `days` defaults to 5 (1–30). `problemsPerDay` defaults to 1 (1–5). The requested window is consecutive, inclusive of the start date.
- `excludeSolved` defaults to true. Set it to false to allow currently solved problems.
- `excludeScheduled` defaults to true and excludes a problem with any planner entry, including earlier or skipped entries. With false, a problem may be proposed on another date. Entries already assigned to the same problem and date still block that slot.
- `candidateLimit` defaults to 15 (1–30). It caps both the candidate query and returned schedule, even if the requested count is larger.

### Output and ranking

SQLite counts eligible problems separately and fetches only the bounded candidate projection. Ranking is `NOT_STARTED`, then `ATTEMPTED`, then `SOLVED` when allowed, with ascending LeetCode ID within each status. Each candidate appears at most once. The planner fills the earliest available dates and may move an earlier recommendation within the proposal to make space for a candidate with fewer available dates. Unfillable dates remain empty. Existing entries are untouched.

The result contains `proposalId` (UUID), `startDate`, `requestedCount` (days × problems per day), `eligibleCount` (before the candidate cap), `selectedCount`, `schedule`, `insufficientCandidates`, and `expiresAt` (UTC timestamp). Each schedule entry contains only `date`, `leetcodeId`, `title`, `difficulty`, and `status`. Comments and catalog metadata are omitted. `insufficientCandidates` is true whenever the plan is short, including when the candidate cap or date conflicts prevent filling it. An empty result still includes the counts and a proposal ID.

Full assignments and internal problem IDs stay in a process-local cache of up to 32 proposals for 10 minutes. The oldest proposal is evicted when the store is full. A server restart clears the cache. This milestone does not create or modify planner entries, progress, goals, or revisions.

### Inspector example

Connect Inspector over Streamable HTTP to the local MCP endpoint. Use **Tools → List Tools**, select `recommend_study_plan`, and enter the example arguments. CLI equivalent:

```sh
npx -y @modelcontextprotocol/inspector@2.16.0 --cli http://127.0.0.1:8080/mcp --transport http \
  --method tools/call --tool-name recommend_study_plan \
  --tool-args-json '{"topicNames":["Dynamic Programming"],"difficulty":"HARD","days":5,"problemsPerDay":1}'
```

## Milestone 7B · Approved study plan commits

`commit_study_plan` accepts only `{"proposalId":"<uuid>"}`. It uses the immutable proposal previously produced by `recommend_study_plan`. It accepts no overrides for dates or problems. Successful results contain `proposalId`, `status: "COMMITTED"`, `createdCount`, and `plannerEntries`. Each entry contains `plannerEntryId`, `leetcodeId`, and `date`. Known rejected attempts return a tool error with the current status, zero created entries, and an `error` message. Invalid or missing IDs return a tool error.

### Configure the local operator

The commit tool requires both `TRACKER_MCP_ENABLED=true` and `TRACKER_MCP_WRITES_ENABLED=true`, plus a 64-character hexadecimal `TRACKER_STUDY_PLAN_APPROVAL_TOKEN`. The token is blank by default. Generate a random token with `openssl rand -hex 32` and keep it in a human-controlled secret store. Supply it to the backend as an environment variable, or use the ignored project `.env` for Docker Compose. Rebuild and restart the backend to apply the configuration. For native development, export the token in the launching shell. Never give the token to an MCP client or agent. The operator credential authorizes approval actions, so keep it accessible only to the trusted operator and server. This workflow assumes clients cannot read the operator's secrets or control the host.

Approval is available via a dedicated localhost HTTP surface. It requires a bearer token on every request, uses no cookies, and rejects browser-origin and Fetch Metadata requests. Approval is unavailable through the MCP tool registry. Bind the app to localhost and use the operator endpoints from a separate terminal.

### Inspect and approve the exact proposal

1. Call `recommend_study_plan` in Inspector, then copy its `proposalId`.
2. In a trusted terminal, set the ID and enter the token without echoing it. The token goes to curl through standard input.

   ```sh
   proposal_id='<paste proposal UUID>'
   approval_url="http://127.0.0.1:8080/api/study-plan-approvals/$proposal_id"
   printf 'Approval token: '
   IFS= read -rs approval_token
   printf '\n'
   operator() {
     printf 'Authorization: Bearer %s\n' "$approval_token" |
       curl --noproxy '*' --fail-with-body --silent --show-error --request POST --header @- "$approval_url/$1"
   }
   operator request
   ```

3. Review the returned schedule, including every date and LeetCode ID. If it matches what you want, run `operator approve`. To reject it, run `operator reject`. Check the returned status, then clear the shell variable with `unset approval_token`.
4. In Inspector, select `commit_study_plan` and pass the same ID. The CLI equivalent is:

   ```sh
   npx -y @modelcontextprotocol/inspector@2.16.0 --cli http://127.0.0.1:8080/mcp --transport http \
     --method tools/call --tool-name commit_study_plan \
     --tool-args-json "{\"proposalId\":\"$proposal_id\"}"
   ```

### State and recovery

A new in-memory proposal is `PROPOSED`. The operator request saves its exact snapshot and moves it to `PENDING_APPROVAL`. That state may become `APPROVED`, `REJECTED`, or `EXPIRED`. Approved proposals may become `COMMITTED`, `FAILED`, or `EXPIRED`. The four terminal states are final. Expiry is checked when the proposal is accessed, and approval does not extend the original ten-minute lifetime. Unrequested proposals may be lost on eviction or restart. Requested snapshots and committed results live in SQLite from migration `V6__study_plan_executions.sql`.

The executor rechecks the exact problem IDs, difficulty, original topic restriction, current progress status, date window, per-day limit, and conflicting assignments. Existing planner entries constrain duplicate dates. Per-day limits apply to the approved batch. The batch and its committed result share one SQLite writer transaction. A conflict or failed insert rolls back the entire batch. After rollback, the service saves a failed result if the plan is still approved. A crash before the commit leaves no partial entries. The approved snapshot may be retried before it expires. A crash after commit leaves the original result available on retry, including after a server restart. Returned IDs describe what the original commit created, even if someone later edits those entries in the planner.

## Milestone 8 · Resources, prompts, and security review

With MCP enabled, these JSON resources are available in every tool profile:

- `tracker://capabilities` describes available workflows and write and approval gates.
- `tracker://topics` lists up to 50 local topic IDs and names with a truncation indicator.
- `tracker://progress/summary` returns live catalog and progress totals from reporting.
- `tracker://planning/rules` describes proposal lifetimes, planner constraints, and approval.
- `tracker://revision/policy` describes scheduled revisions and date semantics.

The prompt registry exposes `create_study_plan(topic, difficulty, days?, problemsPerDay?)`, `analyze_weak_topics()`, `recommend_revisions()`, `discover_new_problems()`, and `weekly_study_review()`. Study-plan defaults are five days and one problem per day. Prompts produce instructions for the client. The operator still approves the exact proposed writes and the client reports success only after the executor confirms it.

### Direct planner approval

MCP `schedule_problem` and `reschedule_problem` require a single-use grant even with writes enabled. Use the same authenticated local operator credential as batch approval. From the trusted terminal described above:

```sh
# For scheduling, use the LeetCode ID. For rescheduling, use the planner entry ID.
action=SCHEDULE
id=4
date=2026-12-01
printf 'Authorization: Bearer %s\n' "$approval_token" |
  curl --noproxy '*' --fail-with-body --silent --show-error --request POST --header @-   "http://127.0.0.1:8080/api/planner-write-approvals/$action/$id/$date"
unset approval_token
```

In Inspector call `schedule_problem` with `{"leetcodeId":4,"date":"2026-12-01"}`, or use `RESCHEDULE` and call `reschedule_problem` with the corresponding `plannerEntryId` and date. A grant covers the exact action, ID, and date. It expires after five minutes and is consumed before execution. If execution fails, review the error and grant again after rechecking the request. Outstanding grants are process-local. The existing REST planner API follows its existing trusted-local policy.

Loopback, Host, and Origin protections still apply. Approval endpoints require a private operator token, reject browser-origin and Fetch Metadata requests, and have no corresponding MCP approval tool. Keep clients away from the operator token and host access. Progress, goal, revision, and import writes use the write switch and rely on explicit client-side user consent. Discovery uses a public HTTPS endpoint or an explicitly configured local test mock. The SQL/path error regression test injects a storage failure and verifies that the tool response omits its internal details.

Topic and goal lists cap at 50, planner lists at 100, and all-time activity at 365 dated rows. Truncation indicators identify incomplete lists. Summary totals still cover the relevant full result. In the 419-problem SQLite fixture, serialized structured responses measured 12,110 bytes for a query page, 7,401 for topics, 9,578 for goals, 13,976 for activity, 19,370 for revisions, and 4,594 for a plan. These measurements exclude the transport envelope and vary with fixture data.

### Inspector protocol checks

Open Inspector, choose **Streamable HTTP**, and connect to `http://127.0.0.1:8080/mcp` in Docker or the native endpoint documented above. Use **Tools** to list tools and call `ping_tracker`, **Resources** to list resources and read `tracker://capabilities`, and **Prompts** to list prompts and fetch `weekly_study_review`. These actions cover `tools/list`, `tools/call`, `resources/list`, `resources/read`, `prompts/list`, and `prompts/get`. The Java MCP client and Inspector CLI both exercised these protocol methods over HTTP with temporary SQLite. To reproduce the checks with Docker:

```sh
endpoint=http://127.0.0.1:8080/mcp
inspector() { npx -y @modelcontextprotocol/inspector@2.16.0 --cli "$endpoint" --transport http "$@"; }
inspector --method tools/list
inspector --method tools/call --tool-name ping_tracker
inspector --method resources/list
inspector --method resources/read --uri tracker://capabilities
inspector --method prompts/list
inspector --method prompts/get --prompt-name weekly_study_review
```

 If connecting with an HTTP utility, use the Streamable HTTP Accept types `application/json, text/event-stream` and initialize a session before making MCP calls.

## Milestone 9 · Typed queries

Set `TRACKER_MCP_TOOL_PROFILE=ALL` to expose the unified and existing tools together. `LEGACY` is the default and exposes the existing tools. `UNIFIED` exposes the typed query tools plus ping, recommendation, LeetCode discovery, and eligible writes. The independent write switch and approval checks apply in all profiles.

- `query_tracker(domain, query)` accepts a strict, domain-specific JSON object. The result is `{domain,page,returnedCount,hasMore,items,truncated}`. Each item follows the selected domain or analytics report.
- `describe_tracker()` returns available domains and filters, enums, limits, and up to 50 local topic choices. Inspect `tools/list` for the full machine-readable input schema.

Dates use ISO `YYYY-MM-DD`. The common page defaults to 0 and supports up to 10,000. The default limit is 20 and the maximum is 100, subject to each report's bounds. Filter arrays allow 1–20 values, paired date windows at most 366 inclusive days, and the query object at most 8 KiB. Explicit nulls, unrecognized fields, and unsupported sort keys are rejected. Tool errors contain `isError` and structured `code` and `message` fields. Codes include `INVALID_QUERY`, `INVALID_DOMAIN`, `UNSUPPORTED_FILTER`, `INVALID_VALUE`, and `MISSING_FIELD`.

- **PROBLEMS** supports `title`, `leetcodeId`, `topicNames`, `difficulty`, `statuses`, `excludeScheduled`, `scheduledFrom`/`scheduledTo`, `attemptedFrom`/`attemptedTo`, `solvedFrom`/`solvedTo`, `sortBy`, and `sortDirection`. Topic and status lists match with OR within each list; separate fields combine with AND. Sort keys are `LEETCODE_ID`, `TITLE`, `CREATED_AT`, and `UPDATED_AT` with `ASC` or `DESC`. The default is ascending LeetCode ID. Scheduling date bounds are inclusive and apply to any assignment, including skipped entries. With exclusion enabled and no bounds, any assignment excludes the problem. With exclusion enabled and date bounds, only assignments within those bounds exclude the problem. With bounds alone, results require a matching assignment. Rows include LeetCode ID, title, difficulty, and progress status.
- **GOALS** supports `activeOn`, `type`, `topicName`, `difficulty`, `completed`, and `startDateFrom`/`startDateTo`. Rows include goal period, restrictions, target, and current progress.
- **PLANNER** supports `startDate`, `endDate`, `state`, and `difficulty`. Rows include the assignment date, effective state, and compact problem metadata.
- **REVISIONS** supports `dueBefore`, `startDate`, `endDate`, and `status`. `dueBefore` is exclusive and selects scheduled revisions. Rows include task status and date classification in the tracker time zone.
- **ANALYTICS** requires `report`, one of `DASHBOARD_SUMMARY`, `TOPIC_PROGRESS`, `DIFFICULTY_PROGRESS`, `SOLVING_ACTIVITY`, or `RECENT_PROBLEMS`. Reports reuse current progress and reporting projections. Range-capable reports accept `range` of `7`, `30`, `90`, or `all`. Dashboard summary accepts only its report selector. See the published schema and validation rules for report-specific controls.

### Inspector examples

Enable MCP with `TRACKER_MCP_TOOL_PROFILE=ALL`, connect Inspector, then select **Tools → List Tools → query_tracker**. Paste one JSON argument object per call:

```json
{"domain":"PROBLEMS","query":{"topicNames":["Dynamic Programming"],"difficulty":"HARD","statuses":["NOT_STARTED","ATTEMPTED"],"excludeScheduled":true,"limit":5}}
{"domain":"GOALS","query":{"completed":false,"limit":5}}
{"domain":"PLANNER","query":{"startDate":"2026-11-23","endDate":"2026-11-29","limit":10}}
{"domain":"REVISIONS","query":{"dueBefore":"2026-11-27","limit":10}}
{"domain":"ANALYTICS","query":{"report":"TOPIC_PROGRESS","range":"30","limit":10}}
```

CLI equivalent for the first query:

```sh
npx -y @modelcontextprotocol/inspector@2.16.0 --cli http://127.0.0.1:8080/mcp --transport http   --method tools/call --tool-name query_tracker   --tool-args-json '{"domain":"PROBLEMS","query":{"topicNames":["Dynamic Programming"],"difficulty":"HARD","limit":5}}'
```

The typed adapter calls existing application services. Problem queries use JPA Specifications and a database slice, goals and planner use pageable queries, revisions use bound SQL parameters, and analytics reuse the reporting projections. SQLite remains authoritative. Query results omit comments and full catalog metadata. The engine answers the declared domains and filters and makes no network calls. Consult the [tool inventory](MCP_TOOL_INVENTORY.md) for legacy equivalents and write gates.

## Verification and limits

- Milestones 8 and 9 passed `mvn clean verify` with 109 backend tests. The HTTP MCP suite exercised all three profiles, resource and prompt discovery and reads, validation, filtered projections, read-only behavior, direct and batch approval, and REST regressions with temporary SQLite and mocked GraphQL.
- Inspector CLI verified all six protocol methods against the packaged backend with temporary SQLite on port 18091. It discovered 21 read tools, five resources, and five prompts. The empty dashboard query returned zero totals. The portability check reported zero errors and ten warnings across seven tools.
- All 37 frontend tests, the standalone TypeScript check, the production build, and merged Docker Compose configuration validation passed.

- At Milestone 7B, `mvn clean verify` passed all 103 backend tests. The V5-to-V6 upgrade preserved existing planner entries. Temporary SQLite tests covered authenticated approval, browser-origin rejection, stale proposals, rollback, concurrency, and exact retries across application restart.
- Compose configuration validated. Inspector verified a five-day approved commit and an identical retry against the packaged backend on loopback port 18090. SQLite contained exactly five planner entries afterward. Inspector reported zero schema errors and 20 portability warnings across 11 tools.

- At Milestone 7A, `mvn clean verify` passed all 96 backend tests. Four new integration cases covered database filtering, deterministic ranking, planner collisions, date and candidate bounds, timezone, proposal expiration and eviction, and zero SQLite writes.
- Inspector verified a five-day recommendation with writes disabled and a temporary SQLite catalog. It reported zero schema errors and ten portability warnings across seven existing tools.

- At Milestone 6B, `mvn clean verify` passed 92 backend tests. Temporary SQLite integration tests verified write-tool gating, selection validation, expiry, topic handling, preservation of solved progress, rollback, concurrent imports, and REST compatibility.
- Inspector verified preview, persisted import, and repeat-import skipping against a packaged backend with mocked GraphQL on loopback port 18088. Only the original preview contacted the mock. Inspector reported zero schema errors and 19 portability warnings across 10 tools.

- At Milestone 6A, `mvn clean verify` passed 88 backend tests, including six new discovery integration cases. The Java MCP client used a local HTTP GraphQL mock and temporary SQLite. Tests covered metadata mapping, pagination, filters, duplicates, lookup caching, previews, invalid inputs, remote failures and timeouts, and zero SQLite writes.
- Inspector CLI discovered the tools with writes disabled and successfully called search, lookup, and preview against the packaged backend with mocked GraphQL responses. Inspector reported zero schema errors and ten portability warnings across seven existing tools. Live LeetCode was not contacted during verification.

- At Milestone 5B, `mvn clean verify` passed 82 backend tests. The V4-to-V5 test preserved existing goals, and integration tests verified filter combinations, partial updates, REST compatibility, and revision completion without changing problem progress. The frontend's 37 tests and production build passed.
- Inspector CLI discovered 21 tools and invoked all three new tools against a packaged backend with temporary SQLite on loopback port 18086. Its schema portability check reported zero errors and 19 warnings across 10 tools.
- Goal difficulty can be set through MCP or REST. The current web goal form has no difficulty selector.

- At Milestone 5A, `mvn clean verify` passed 76 backend tests. MCP integration tests verified 15 read tools with writes disabled and 18 tools with writes enabled. Temporary SQLite tests verified progress persistence, omitted and null values, duplicate scheduling, completion and skip rules, REST compatibility, and rollback on an injected revision failure.
- Compose configuration validated with either value of the writes switch. Inspector CLI discovered and invoked all three enabled write tools against a packaged backend on loopback port 18085 with a temporary database. Its portability check reported zero errors and 14 warnings across eight tools. Some clients may require adapting nullable schemas.

- At Milestone 4, `mvn clean verify` passed 71 backend tests, including 29 MCP integration cases. Tests exercised planner counts, goal progress, bounded revision queries, invalid inputs, existing REST response shapes, and SQLite read-only behavior with temporary databases.
- Inspector CLI discovered all 15 tools and called all six study tools against the packaged backend on loopback port 18084. Its portability check reported zero schema errors and ten warnings across seven tools. Clients with restricted schema dialects may require adapting nullable fields.
- All 37 frontend tests and the production frontend build passed at Milestone 4.

- At Milestone 3, `mvn clean verify` passed 66 backend tests, including 24 MCP integration cases against temporary SQLite. Coverage includes existing REST response shapes, no commits during tool calls, date boundaries in the configured timezone, and invalid input.
- Inspector CLI discovered nine tools and verified structured results for all five reporting tools against the packaged backend on loopback port 18083. Its schema portability check reported zero errors and six warnings across four tools. Some clients may require adapting the nullable date schemas.

- At Milestone 2, `mvn clean verify` passed 61 backend tests, including 19 MCP integration cases over real HTTP with a temporary SQLite database. They cover tool registration, filters, pagination, error handling, and read-only behavior. A separate SQLite observer confirmed that successful catalog tool calls committed no writes.
- Inspector CLI discovered all four tools and successfully called all three catalog tools against the packaged backend with a temporary database on loopback port 18082.
- Inspector strict mode reported zero schema errors and four portability warnings for nullable date fields. The fields use standard JSON Schema unions for ISO date strings or null. Clients that require a single type per property may need to adapt the schema.
- Regression tests cover MCP disabled by default, malformed MCP requests returning HTTP 400, foreign Origin requests returning HTTP 403, and REST health returning HTTP 200.
- At Milestone 1, Inspector also listed and invoked the diagnostic tool against a packaged backend on loopback port 18081. Manual checks returned HTTP 400 for malformed JSON and HTTP 403 for a non-local Host. The web UI steps are provided for manual testing.
- The frontend's 37 tests and production build passed.
- MCP is intended for direct loopback clients. The endpoint rejects non-loopback peers and cross-origin browser requests, and the application continues to enforce local Host headers. Docker forwards `/mcp` through its loopback-only frontend port. The route returns HTTP 404 while MCP is disabled.
- `ping_tracker` verifies MCP connectivity. Use REST `/api/health` for the database health check.
