package dev.dsatracker.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.enabled=true",
                "tracker.study-plan-approval-token=0000000000000000000000000000000000000000000000000000000000000000"})
class TrackerMcpIntegrationTest {

    private static final JsonMapper JSON = new JsonMapper();

    @TestConfiguration
    static class FixedReportingTime {
        @Bean
        @Primary
        Clock reportingClock() {
            return Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), ZoneId.of("Asia/Kolkata"));
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    static Path temporaryDirectory;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("test.sqlite").toString());
        properties.add("tracker.backup-root", () -> temporaryDirectory.resolve("backups").toString());
    }

    @Test
    void initializesListsAndCallsTheDiagnosticTool() {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl()).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("dsa-tracker");
            var tools = client.listTools().tools();
            assertThat(tools).extracting(Tool::name)
                    .containsExactlyInAnyOrder("ping_tracker", "search_problems", "get_problem", "list_topics",
                            "get_dashboard_summary", "get_topic_progress", "get_difficulty_progress",
                            "get_solving_activity", "get_recent_problems", "get_daily_plan", "get_weekly_plan",
                            "list_goals", "get_goal_progress", "get_due_revisions", "get_upcoming_revisions",
                            "search_leetcode_problems", "get_leetcode_problem", "preview_leetcode_import",
                            "recommend_study_plan");
            assertThat(tools).allSatisfy(tool -> {
                assertThat(tool.annotations().readOnlyHint()).isTrue();
                assertThat(tool.outputSchema()).isNotEmpty();
            });
            assertThat(tools.stream().filter(tool -> tool.name().equals("get_problem")).findFirst().orElseThrow()
                    .inputSchema().get("required")).isEqualTo(List.of("leetcodeId"));

            var result = client.callTool(new CallToolRequest("ping_tracker", Map.of()));
            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContent()).isEqualTo(Map.of(
                    "status", "UP",
                    "application", "DSA Tracker",
                    "message", "MCP server connected successfully"));
        }
    }

    @Test
    void rejectsMalformedRequestsAndForeignOriginsWithoutBreakingRestHealth() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var malformed = client.send(mcpRequest().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(malformed.statusCode()).isEqualTo(400);

            var foreignOrigin = client.send(mcpRequest().header("Origin", "https://tracker.invalid").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(foreignOrigin.statusCode()).isEqualTo(403);

            var healthRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var health = client.send(healthRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @BeforeEach
    void seedCatalog() {
        jdbc.update("DELETE FROM goals");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
        String timestamp = "2025-01-01T12:00:00Z";
        jdbc.update("INSERT INTO topics (id, name, name_key, created_at) VALUES "
                + "(1, 'Array', 'array', ?), (2, 'Dynamic Programming', 'dynamic programming', ?),"
                + "(3, 'Prefix Sum', 'prefix sum', ?)", timestamp, timestamp, timestamp);
        insertProblem(11, 1, "Two Sum", "two-sum", "EASY", timestamp);
        insertProblem(12, 70, "Climbing Stairs", "climbing-stairs", "EASY", timestamp);
        insertProblem(13, 862, "Shortest Subarray with Sum at Least K", "shortest-subarray-with-sum-at-least-k",
                "HARD", timestamp);
        jdbc.update("INSERT INTO problem_topics (problem_id, topic_id) VALUES (11, 1), (12, 2), (13, 1), (13, 3)");
        jdbc.update("""
                INSERT INTO problem_progress (problem_id, status, attempted_date, solved_date, comments, updated_at)
                VALUES (11, 'NOT_STARTED', NULL, NULL, '', ?),
                       (12, 'ATTEMPTED', '2025-01-01', NULL, '', ?),
                       (13, 'SOLVED', '2025-01-01', '2025-01-02', 'Use a monotonic queue.', ?)
                """, timestamp, timestamp, timestamp);
    }

    private void insertProblem(int id, int leetcodeId, String title, String slug, String difficulty, String timestamp) {
        jdbc.update("""
                INSERT INTO problems (id, leetcode_id, title, slug, url, difficulty, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, leetcodeId, title, slug, "https://leetcode.com/problems/" + slug + "/", difficulty,
                timestamp, timestamp);
    }

    static Stream<Arguments> searches() {
        return Stream.of(
                Arguments.of(Map.of(), List.of(1, 70, 862)),
                Arguments.of(Map.of("leetcodeId", 862), List.of(862)),
                Arguments.of(Map.of("query", "862"), List.of(862)),
                Arguments.of(Map.of("query", "CLIMBING"), List.of(70)),
                Arguments.of(Map.of("topicNames", List.of(" aRRay ")), List.of(1, 862)),
                Arguments.of(Map.of("difficulty", "HARD"), List.of(862)),
                Arguments.of(Map.of("status", "ATTEMPTED"), List.of(70)),
                Arguments.of(Map.of("query", "sum", "leetcodeId", 862, "topicNames", List.of("array"),
                        "difficulty", "HARD", "status", "SOLVED"), List.of(862)),
                Arguments.of(Map.of("topicNames", List.of("Array", "Dynamic Programming", "Prefix Sum")),
                        List.of(1, 70, 862)),
                Arguments.of(Map.of("topicNames", List.of("missing topic")), List.of()),
                Arguments.of(Map.of("topicNames", List.of("missing topic", "Prefix Sum")), List.of(862)),
                Arguments.of(Map.of("query", "unmatched"), List.of()),
                Arguments.of(Map.of("query", "climbing", "leetcodeId", 862), List.of()));
    }

    @ParameterizedTest
    @MethodSource("searches")
    void searchesCatalog(Map<String, Object> filters, List<Integer> expectedIds) {
        try (var client = connect()) {
            var result = read(client, "search_problems", filters);
            assertThat(result.path("items").valueStream().map(item -> item.path("leetcodeId").asInt()).toList())
                    .isEqualTo(expectedIds);
            assertThat(result.path("page").asInt()).isZero();
            assertThat(result.path("size").asInt()).isEqualTo(20);
            assertThat(result.path("totalItems").asInt()).isEqualTo(expectedIds.size());
            assertThat(result.path("totalPages").asInt()).isEqualTo(expectedIds.isEmpty() ? 0 : 1);
            if (expectedIds.contains(1)) {
                var first = result.path("items").path(0);
                assertThat(first.path("status").asString()).isEqualTo("NOT_STARTED");
                assertThat(first.path("attemptedDate").isNull()).isTrue();
                assertThat(first.path("solvedDate").isNull()).isTrue();
            }
        }
    }

    @Test
    void paginatesAndValidatesInputs() {
        try (var client = connect()) {
            var page = read(client, "search_problems", Map.of("page", 1, "size", 1));
            assertThat(page.path("items").path(0).path("leetcodeId").asInt()).isEqualTo(70);
            assertThat(page.path("totalItems").asInt()).isEqualTo(3);
            assertThat(page.path("totalPages").asInt()).isEqualTo(3);
            assertThat(read(client, "search_problems", Map.of("size", 100)).path("size").asInt()).isEqualTo(100);
            assertError(client, "search_problems", Map.of("size", 101), "Page size must be from 1 to 100");
            assertError(client, "search_problems", Map.of("page", -1), "non-negative");
            assertError(client, "search_problems", Map.of("topicNames", List.of(" ")), "nonblank topic names");
            assertError(client, "search_problems", Map.of("leetcodeId", 0), "LeetCode ID must be positive");
            assertError(client, "list_topics", Map.of("query", "x".repeat(81)), "at most 80 characters");
            assertError(client, "search_problems", Map.of("size", 1.5), "size");
            assertError(client, "search_problems", Map.of("difficulty", "IMPOSSIBLE"), "difficulty");
            assertError(client, "get_problem", Map.of(), "leetcodeId");
        }
    }

    @Test
    void returnsFullProblemProgressAndMeaningfulNotFoundError() {
        try (var client = connect()) {
            var problem = read(client, "get_problem", Map.of("leetcodeId", 862));
            assertThat(problem.path("id").asInt()).isEqualTo(13);
            assertThat(problem.path("slug").asString()).isEqualTo("shortest-subarray-with-sum-at-least-k");
            assertThat(problem.path("url").asString())
                    .isEqualTo("https://leetcode.com/problems/shortest-subarray-with-sum-at-least-k/");
            assertThat(problem.path("difficulty").asString()).isEqualTo("HARD");
            assertThat(problem.path("topics").valueStream().map(JsonNode::asString).toList())
                    .containsExactly("Array", "Prefix Sum");
            assertThat(problem.path("progress")).isEqualTo(JSON.readTree("""
                    {"status":"SOLVED","attemptedDate":"2025-01-01","solvedDate":"2025-01-02",
                     "comments":"Use a monotonic queue."}
                    """));
            var unstarted = read(client, "get_problem", Map.of("leetcodeId", 1)).path("progress");
            assertThat(unstarted.path("attemptedDate").isNull()).isTrue();
            assertThat(unstarted.path("solvedDate").isNull()).isTrue();
            assertError(client, "get_problem", Map.of("leetcodeId", 12345), "No problem found with LeetCode ID 12345");
        }
    }

    @Test
    void listsTopicsWithCaseInsensitiveSubstringMatching() {
        try (var client = connect()) {
            var all = read(client, "list_topics", Map.of()).path("topics");
            assertThat(all.valueStream().map(topic -> topic.path("name").asString()).toList())
                    .containsExactly("Array", "Dynamic Programming", "Prefix Sum");
            var filtered = read(client, "list_topics", Map.of("query", "pROGram")).path("topics");
            assertThat(filtered).isEqualTo(JSON.readTree("""
                    [{"id":2,"name":"Dynamic Programming"}]
                    """));
            assertThat(read(client, "list_topics", Map.of("query", "%")).path("topics").isEmpty()).isTrue();
        }
    }

    @Test
    void catalogToolsDoNotCommitDatabaseChanges() throws SQLException {
        try (var observer = DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("test.sqlite"));
                var client = connect()) {
            int originalVersion = dataVersion(observer);
            read(client, "search_problems", Map.of("topicNames", List.of("Array", "unknown")));
            read(client, "search_problems", Map.of("topicNames", List.of("unknown")));
            read(client, "get_problem", Map.of("leetcodeId", 862));
            read(client, "list_topics", Map.of("query", "array"));
            read(client, "get_dashboard_summary", Map.of());
            read(client, "get_topic_progress", Map.of());
            read(client, "get_difficulty_progress", Map.of());
            read(client, "get_solving_activity", Map.of());
            read(client, "get_recent_problems", Map.of());
            assertThat(dataVersion(observer)).isEqualTo(originalVersion);
        }
    }

    @Test
    void reportsEmptyCatalog() {
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
        try (var client = connect()) {
            assertThat(read(client, "get_dashboard_summary", Map.of())).isEqualTo(JSON.readTree("""
                    {"totalProblems":0,"solved":0,"attempted":0,"notStarted":0,"solvedPercentage":0.0}
                    """));
            assertThat(read(client, "get_topic_progress", Map.of()).path("topics").isEmpty()).isTrue();
            var difficulty = read(client, "get_difficulty_progress", Map.of());
            assertThat(difficulty.path("range").asString()).isEqualTo("all");
            assertThat(difficulty.path("difficulties")).isEqualTo(JSON.readTree("""
                    [{"difficulty":"EASY","solvedCount":0},{"difficulty":"MEDIUM","solvedCount":0},
                     {"difficulty":"HARD","solvedCount":0}]
                    """));
            var activity = read(client, "get_solving_activity", Map.of());
            assertThat(activity.path("range").asString()).isEqualTo("30");
            assertThat(activity.path("days").isEmpty()).isTrue();
            assertThat(activity.path("totalSolvesInRange").asInt()).isZero();
            var recent = read(client, "get_recent_problems", Map.of());
            assertThat(recent.path("recentlySolved").isEmpty()).isTrue();
            assertThat(recent.path("recentlyModified").isEmpty()).isTrue();
        }
    }

    @Test
    void reportsCurrentStatusesMultiTopicMembershipAndRestContract() throws Exception {
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var dashboard = read(client, "get_dashboard_summary", Map.of());
            assertThat(dashboard.path("totalProblems").asInt()).isEqualTo(3);
            assertThat(dashboard.path("solved").asInt()).isEqualTo(1);
            assertThat(dashboard.path("attempted").asInt()).isEqualTo(1);
            assertThat(dashboard.path("notStarted").asInt()).isEqualTo(1);
            assertThat(dashboard.path("solvedPercentage").asDouble()).isEqualTo(100.0 / 3);

            var all = read(client, "get_topic_progress", Map.of()).path("topics");
            assertThat(all.path(0)).isEqualTo(JSON.readTree("""
                    {"topicId":1,"topicName":"Array","totalProblems":2,"solvedProblems":1,
                     "unsolvedProblems":1,"solvedProblemsAllTime":1,"solvedPercentage":50.0}
                    """));
            assertThat(all.path(2).path("solvedProblems").asInt()).isEqualTo(1);
            var bounded = read(client, "get_topic_progress", Map.of("range", "7")).path("topics").path(0);
            assertThat(bounded.path("solvedProblems").asInt()).isZero();
            assertThat(bounded.path("solvedProblemsAllTime").asInt()).isEqualTo(1);
            assertThat(bounded.path("unsolvedProblems").asInt()).isEqualTo(1);
            assertThat(bounded.path("solvedPercentage").asDouble()).isZero();

            assertThat(restJson(http, "/api/dashboard/summary")).isEqualTo(JSON.readTree("""
                    {"total":3,"solved":1,"attempted":1,"notStarted":1}
                    """));
            assertThat(restJson(http, "/api/analytics/topics?range=7").path(0)).isEqualTo(JSON.readTree("""
                    {"topicId":1,"name":"Array","total":2,"solved":0,"remaining":1}
                    """));
            assertThat(restJson(http, "/api/analytics/difficulty")).isEqualTo(JSON.readTree("""
                    [{"difficulty":"HARD","solved":1}]
                    """));
        }
    }

    @Test
    void usesLocalDayBoundariesAndCurrentSolvedDates() throws Exception {
        jdbc.update("UPDATE problems SET difficulty = 'MEDIUM' WHERE id = 12");
        jdbc.update("UPDATE problem_progress SET status = 'SOLVED', solved_date = '2026-11-14' WHERE problem_id = 11");
        jdbc.update("UPDATE problem_progress SET status = 'SOLVED', solved_date = '2026-11-20' WHERE problem_id = 12");
        jdbc.update("UPDATE problem_progress SET solved_date = '2026-11-13' WHERE problem_id = 13");
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var activity = read(client, "get_solving_activity", Map.of("range", "7"));
            assertThat(activity.path("startDate").asString()).isEqualTo("2026-11-14");
            assertThat(activity.path("endDate").asString()).isEqualTo("2026-11-20");
            assertThat(activity.path("days").valueStream().map(day -> day.path("solvedCount").asInt()).toList())
                    .containsExactly(1, 0, 0, 0, 0, 0, 1);
            assertThat(activity.path("totalSolvesInRange").asInt()).isEqualTo(2);
            assertThat(read(client, "get_solving_activity", Map.of()).path("days").size()).isEqualTo(30);
            assertThat(read(client, "get_solving_activity", Map.of("range", "90")).path("days").size()).isEqualTo(90);
            var all = read(client, "get_solving_activity", Map.of("range", "all"));
            assertThat(all.path("startDate").isNull()).isTrue();
            assertThat(all.path("days").size()).isEqualTo(3);
            assertThat(all.path("totalSolvesInRange").asInt()).isEqualTo(3);
            assertThat(read(client, "get_difficulty_progress", Map.of("range", "7")).path("difficulties"))
                    .isEqualTo(JSON.readTree("""
                            [{"difficulty":"EASY","solvedCount":1},{"difficulty":"MEDIUM","solvedCount":1},
                             {"difficulty":"HARD","solvedCount":0}]
                            """));
            assertThat(read(client, "get_difficulty_progress", Map.of()).path("difficulties").path(2)
                    .path("solvedCount").asInt()).isEqualTo(1);
            var rest = restJson(http, "/api/analytics/activity?range=7");
            assertThat(rest.path("endDate").asString()).isEqualTo("2026-11-20");
            assertThat(rest.path("days").path(0)).isEqualTo(JSON.readTree("""
                    {"date":"2026-11-14","solved":1}
                    """));
        }
    }

    @Test
    void treatsLegacyUndatedSolvesConsistently() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var sql = connection.createStatement()) {
                sql.execute("PRAGMA ignore_check_constraints = ON");
                try {
                    sql.executeUpdate("UPDATE problem_progress SET solved_date = NULL WHERE problem_id = 13");
                } finally {
                    sql.execute("PRAGMA ignore_check_constraints = OFF");
                }
            }
            return null;
        });
        try (var client = connect()) {
            assertThat(read(client, "get_dashboard_summary", Map.of()).path("solved").asInt()).isEqualTo(1);
            assertThat(read(client, "get_topic_progress", Map.of()).path("topics").path(0)
                    .path("solvedProblems").asInt()).isEqualTo(1);
            assertThat(read(client, "get_difficulty_progress", Map.of()).path("difficulties").path(2)
                    .path("solvedCount").asInt()).isEqualTo(1);
            assertThat(read(client, "get_solving_activity", Map.of("range", "all"))
                    .path("totalSolvesInRange").asInt()).isZero();
            var recent = read(client, "get_recent_problems", Map.of());
            assertThat(recent.path("recentlySolved").isEmpty()).isTrue();
            assertThat(recent.path("recentlyModified").path(0).path("solvedDate").isNull()).isTrue();
        }
    }

    @Test
    void appliesRecentLimitsAndRejectsInvalidFilters() throws Exception {
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var recent = read(client, "get_recent_problems", Map.of("limit", 1));
            assertThat(recent.path("recentlySolved")).isEqualTo(JSON.readTree("""
                    [{"id":13,"leetcodeId":862,"title":"Shortest Subarray with Sum at Least K",
                      "status":"SOLVED","solvedDate":"2025-01-02"}]
                    """));
            assertThat(recent.path("recentlyModified").size()).isEqualTo(1);
            assertThat(read(client, "get_recent_problems", Map.of()).path("recentlyModified").size()).isEqualTo(3);
            assertThat(read(client, "get_recent_problems", Map.of("limit", 50))
                    .path("recentlyModified").size()).isEqualTo(3);
            for (var tool : List.of("get_topic_progress", "get_difficulty_progress", "get_solving_activity")) {
                assertError(client, tool, Map.of("range", "31"), "Choose 7, 30, 90, or all");
            }
            assertError(client, "get_recent_problems", Map.of("limit", 0), "from 1 to 50");
            assertError(client, "get_recent_problems", Map.of("limit", 51), "from 1 to 50");
            assertError(client, "get_recent_problems", Map.of("limit", 1.5), "limit");
            var rest = restJson(http, "/api/analytics/recent?limit=1");
            assertThat(rest.path("solved").size()).isEqualTo(1);
            assertThat(rest.path("modified").size()).isEqualTo(1);
            assertThat(rest.path("solved").path(0).path("difficulty").asString()).isEqualTo("HARD");
        }
    }

    @Test
    void reportsDailyAndWeeklyPlannerStatesWithoutChangingRestShape() throws Exception {
        jdbc.update("UPDATE problem_progress SET solved_date = '2026-11-20' WHERE problem_id = 13");
        jdbc.update("""
                INSERT INTO plan_items (id, problem_id, planned_date, skipped)
                VALUES (21, 13, '2026-11-20', 0), (22, 12, '2026-11-20', 1),
                       (23, 13, '2026-11-16', 0), (24, 11, '2026-11-22', 0),
                       (25, 11, '2026-11-23', 0)
                """);
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var day = read(client, "get_daily_plan", Map.of());
            assertThat(day.path("date").asString()).isEqualTo("2026-11-20");
            assertThat(day.path("totalScheduled").asInt()).isEqualTo(2);
            assertThat(day.path("completedCount").asInt()).isEqualTo(1);
            assertThat(day.path("skippedCount").asInt()).isEqualTo(1);
            assertThat(day.path("scheduledProblems").path(0)).isEqualTo(JSON.readTree("""
                    {"plannerEntryId":21,"problemId":13,"leetcodeId":862,"title":"Shortest Subarray with Sum at Least K",
                     "difficulty":"HARD","progressStatus":"SOLVED","scheduledDate":"2026-11-20",
                     "completed":true,"skipped":false}
                    """));
            var skipped = day.path("scheduledProblems").path(1);
            assertThat(skipped.path("skipped").asBoolean()).isTrue();
            assertThat(skipped.path("completed").asBoolean()).isFalse();
            var week = read(client, "get_weekly_plan", Map.of("date", "2026-11-22"));
            assertThat(week.path("startDate").asString()).isEqualTo("2026-11-16");
            assertThat(week.path("endDate").asString()).isEqualTo("2026-11-22");
            assertThat(week.path("totalScheduled").asInt()).isEqualTo(4);
            assertThat(week.path("completedCount").asInt()).isEqualTo(1);
            assertThat(week.path("skippedCount").asInt()).isEqualTo(1);
            assertThat(week.path("days").size()).isEqualTo(7);
            assertThat(week.path("days").path(0).path("completedCount").asInt()).isZero();
            assertThat(week.path("days").path(6).path("totalScheduled").asInt()).isEqualTo(1);
            var restTask = restJson(http, "/api/planner?date=2026-11-20").path("days").path(0).path("items").path(0);
            assertThat(restTask.path("state").asString()).isEqualTo("COMPLETED");
            assertThat(restTask.has("difficulty")).isFalse();
        }
    }

    @Test
    void reportsEmptyPlannerAndRevisionLists() {
        try (var client = connect()) {
            var day = read(client, "get_daily_plan", Map.of("date", "2026-11-19"));
            assertThat(day.path("totalScheduled").asInt()).isZero();
            assertThat(day.path("scheduledProblems").isEmpty()).isTrue();
            var week = read(client, "get_weekly_plan", Map.of());
            assertThat(week.path("totalScheduled").asInt()).isZero();
            assertThat(week.path("days").size()).isEqualTo(7);
            assertThat(read(client, "list_goals", Map.of()).path("goals").isEmpty()).isTrue();
            assertThat(read(client, "get_due_revisions", Map.of()).path("revisions").isEmpty()).isTrue();
            assertThat(read(client, "get_upcoming_revisions", Map.of()).path("revisions").isEmpty()).isTrue();
        }
    }

    @Test
    void filtersActiveGoalsAndUsesSavedSolveDatesAndTopicMembership() throws Exception {
        seedStudyGoals();
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var all = read(client, "list_goals", Map.of()).path("goals");
            assertThat(all.size()).isEqualTo(4);
            var active = read(client, "list_goals", Map.of("activeOnly", true)).path("goals");
            assertThat(active.valueStream().map(goal -> goal.path("goalId").asInt()).toList())
                    .containsExactlyInAnyOrder(31, 32);
            var goal = read(client, "get_goal_progress", Map.of("goalId", 31));
            assertThat(goal).isEqualTo(JSON.readTree("""
                    {"goalId":31,"name":"Array week","type":"WEEKLY","startDate":"2026-11-16",
                     "endDate":"2026-11-22","targetCount":2,"solvedCount":1,"remainingCount":1,
                     "completionPercentage":50.0,"complete":false,"topicId":1,"topicName":"Array","difficulty":null}
                    """));
            var unrestricted = read(client, "get_goal_progress", Map.of("goalId", 32));
            assertThat(unrestricted.path("topicId").isNull()).isTrue();
            assertThat(unrestricted.path("solvedCount").asInt()).isEqualTo(2);
            assertThat(unrestricted.path("remainingCount").asInt()).isZero();
            assertThat(unrestricted.path("completionPercentage").asDouble()).isEqualTo(100);
            assertThat(unrestricted.path("complete").asBoolean()).isTrue();
            assertThat(restJson(http, "/api/goals/31/progress")).isEqualTo(JSON.readTree("""
                    {"solvedCount":1,"targetCount":2,"remainingCount":1,"complete":false}
                    """));
            assertError(client, "get_goal_progress", Map.of("goalId", 999), "Goal not found");
        }
    }

    @Test
    void reportsDueAndUpcomingRevisionBoundariesAndLatestReviewInTrackerTimezone() throws Exception {
        seedStudyRevisions();
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var due = read(client, "get_due_revisions", Map.of());
            assertThat(due.path("date").asString()).isEqualTo("2026-11-20");
            assertThat(revisionIds(due)).containsExactly(41, 42);
            assertThat(due.path("revisions").path(0).path("overdue").asBoolean()).isTrue();
            assertThat(due.path("revisions").path(1).path("overdue").asBoolean()).isFalse();
            assertThat(revisionIds(read(client, "get_due_revisions", Map.of("date", "2026-11-19"))))
                    .containsExactly(41);
            assertThat(revisionIds(read(client, "get_due_revisions", Map.of("limit", 1)))).containsExactly(41);
            var upcoming = read(client, "get_upcoming_revisions", Map.of());
            assertThat(upcoming.path("fromDate").asString()).isEqualTo("2026-11-20");
            assertThat(upcoming.path("endDate").asString()).isEqualTo("2026-11-26");
            assertThat(revisionIds(upcoming)).containsExactly(42, 43);
            assertThat(upcoming.path("revisions").path(0).path("latestReviewedDate").asString())
                    .isEqualTo("2026-11-20");
            assertThat(upcoming.path("revisions").path(1).path("latestReviewedDate").isNull()).isTrue();
            assertThat(upcoming.path("revisions").path(1).path("difficulty").asString()).isEqualTo("EASY");
            assertThat(revisionIds(read(client, "get_upcoming_revisions",
                    Map.of("fromDate", "2026-11-26", "days", 2, "limit", 100)))).containsExactly(43, 44);
            assertThat(revisionIds(read(client, "get_upcoming_revisions", Map.of("days", 90, "limit", 1))))
                    .containsExactly(42);
            var rest = restJson(http, "/api/revisions?filter=UPCOMING");
            assertThat(rest.path("items").size()).isEqualTo(2);
            assertThat(rest.path("items").path(0).path("scheduledDate").asString()).isEqualTo("2026-11-26");
            assertThat(rest.path("items").path(0).has("latestReviewedDate")).isFalse();
        }
    }

    @Test
    void studyToolsValidateInputsAndDoNotCommitWrites() throws SQLException {
        seedStudyGoals();
        seedStudyRevisions();
        try (var observer = DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("test.sqlite"));
                var client = connect()) {
            int originalVersion = dataVersion(observer);
            read(client, "get_daily_plan", Map.of());
            read(client, "get_weekly_plan", Map.of());
            read(client, "list_goals", Map.of());
            read(client, "get_goal_progress", Map.of("goalId", 31));
            read(client, "get_due_revisions", Map.of());
            read(client, "get_upcoming_revisions", Map.of());
            assertError(client, "get_daily_plan", Map.of("date", "2026-02-30"), "YYYY-MM-DD");
            assertError(client, "get_weekly_plan", Map.of("date", "2026-1-02"), "YYYY-MM-DD");
            assertError(client, "get_due_revisions", Map.of("date", "0000-01-01"), "YYYY-MM-DD");
            assertError(client, "get_upcoming_revisions", Map.of("fromDate", "tomorrow"), "YYYY-MM-DD");
            assertError(client, "get_upcoming_revisions", Map.of("fromDate", "9999-12-31"), "0001 to 9999");
            assertError(client, "get_upcoming_revisions", Map.of("days", 91), "from 1 to 90");
            assertError(client, "get_upcoming_revisions", Map.of("limit", 101), "from 1 to 100");
            assertError(client, "get_due_revisions", Map.of("limit", 0), "from 1 to 100");
            assertError(client, "get_goal_progress", Map.of("goalId", 0), "Goal ID must be positive");
            assertThat(dataVersion(observer)).isEqualTo(originalVersion);
        }
    }

    private void seedStudyGoals() {
        jdbc.update("UPDATE problem_progress SET solved_date = '2026-11-20' WHERE problem_id = 13");
        jdbc.update("UPDATE problem_progress SET status = 'SOLVED', solved_date = '2026-11-20' WHERE problem_id = 12");
        jdbc.update("""
                INSERT INTO goals (id, name, type, start_date, end_date, target_count, topic_id)
                VALUES (31, 'Array week', 'WEEKLY', '2026-11-16', '2026-11-22', 2, 1),
                       (32, 'Today', 'DAILY', '2026-11-20', '2026-11-20', 1, NULL),
                       (33, 'Expired', 'DAILY', '2026-11-19', '2026-11-19', 1, NULL),
                       (34, 'Future', 'DAILY', '2026-11-21', '2026-11-21', 1, NULL)
                """);
    }

    private void seedStudyRevisions() {
        jdbc.update("""
                INSERT INTO revision_tasks (id, problem_id, scheduled_date, status, completed_at, created_at, updated_at)
                VALUES (41, 13, '2026-11-19', 'SCHEDULED', NULL, ?, ?),
                       (42, 13, '2026-11-20', 'SCHEDULED', NULL, ?, ?),
                       (43, 12, '2026-11-26', 'SCHEDULED', NULL, ?, ?),
                       (44, 13, '2026-11-27', 'SCHEDULED', NULL, ?, ?),
                       (45, 13, '2026-11-17', 'COMPLETED', '2026-11-19T19:00:00.000Z', ?, ?),
                       (46, 13, '2026-11-18', 'CANCELLED', NULL, ?, ?),
                       (47, 13, '2026-11-16', 'COMPLETED', '2026-11-18T19:00:00.000Z', ?, ?)
                """, java.util.Collections.nCopies(14, "2026-11-13T12:00:00.000Z").toArray());
    }

    private static List<Integer> revisionIds(JsonNode payload) {
        return payload.path("revisions").valueStream().map(item -> item.path("revisionId").asInt()).toList();
    }

    private JsonNode restJson(HttpClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(5)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static int dataVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var version = statement.executeQuery("PRAGMA data_version")) {
            version.next();
            return version.getInt(1);
        }
    }

    private McpSyncClient connect() {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl()).build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private static JsonNode read(McpSyncClient client, String tool, Map<String, Object> arguments) {
        var result = client.callTool(new CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("%s result: %s", tool, result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    private static void assertError(McpSyncClient client, String tool, Map<String, Object> arguments, String message) {
        var result = client.callTool(new CallToolRequest(tool, arguments));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).anySatisfy(content -> {
            assertThat(content).isInstanceOf(TextContent.class);
            assertThat(((TextContent) content).text()).contains(message);
        });
    }

    private HttpRequest.Builder mcpRequest() {
        return HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{"));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
