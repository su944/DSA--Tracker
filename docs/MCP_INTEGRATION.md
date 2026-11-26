# MCP integration

The existing Java 21 Spring Boot application serves REST and can opt in to a local MCP endpoint. The server exposes `ping_tracker`, three read-only catalog tools, and five read-only reporting tools. Tool results include structured JSON. Catalog tools read the local SQLite catalog through the same application services used by REST.

## Dependencies and transport

Spring AI **2.0.3** is in the 2.0 release line that supports Spring Boot **4.1.x**. The project keeps its Spring Boot **4.1.3** parent, imports `org.springframework.ai:spring-ai-bom:2.0.3`, and uses `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` to match the existing servlet stack. The starter supplies MCP Java SDK **2.1.0** and registers the annotated tools. [Spring AI compatibility](https://github.com/spring-projects/spring-ai), [Spring AI 2.0.3 release](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/).

```properties
server.address=127.0.0.1
spring.ai.mcp.server.enabled=${TRACKER_MCP_ENABLED:false}
spring.ai.mcp.server.name=dsa-tracker
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp
spring.ai.mcp.server.tool-callback-converter=false
spring.ai.mcp.server.capabilities.resource=false
spring.ai.mcp.server.capabilities.prompt=false
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

## Verification and limits

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
