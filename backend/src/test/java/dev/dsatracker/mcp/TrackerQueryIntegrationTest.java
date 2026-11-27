package dev.dsatracker.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dsatracker.TrackerApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.enabled=true", "tracker.mcp-tool-profile=ALL"})
class TrackerQueryIntegrationTest {
    private static final JsonMapper JSON = new JsonMapper();
    @TempDir static Path directory;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> directory.resolve("query.sqlite").toString());
        properties.add("tracker.backup-root", () -> directory.resolve("backups").toString());
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM goals");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
        jdbc.update("""
                INSERT INTO topics VALUES
                (1,'Dynamic Programming','dynamic programming','2026-11-20T00:00:00Z'),
                (2,'Arrays','arrays','2026-11-20T00:00:00Z')
                """);
        problem(1, "HARD", "NOT_STARTED", null);
        problem(2, "HARD", "ATTEMPTED", null);
        problem(3, "HARD", "SOLVED", "2026-11-20");
        problem(4, "EASY", "NOT_STARTED", null);
        jdbc.update("INSERT INTO problem_topics VALUES (1,1),(1,2),(2,2),(3,1),(4,1)");
        jdbc.update("INSERT INTO plan_items(problem_id,planned_date,skipped) VALUES (2,'2026-11-20',1),(3,'2026-11-20',0),(3,'2026-11-21',0)");
        jdbc.update("""
                INSERT INTO goals(name,type,start_date,end_date,target_count,topic_id,difficulty)
                VALUES ('Goal','WEEKLY','2026-11-16','2026-11-22',1,1,'HARD'),
                       ('Open','CUSTOM','2026-11-19','2026-11-22',3,NULL,NULL)
                """);
        jdbc.update("""
                INSERT INTO revision_tasks(problem_id,scheduled_date,status,created_at,updated_at)
                VALUES (3,'2026-11-20','SCHEDULED','2026-11-20T00:00:00Z','2026-11-20T00:00:00Z'),
                       (3,'9999-01-01','SCHEDULED','2026-11-20T00:00:00Z','2026-11-20T00:00:00Z')
                """);
    }

    @Test
    void allProfileExecutesBoundedStrictProblemFiltersWithoutWriting() throws Exception {
        try (var client = connect(port);
                var observer = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("query.sqlite"));
                var statement = observer.createStatement()) {
            int before;
            try (var result = statement.executeQuery("PRAGMA data_version")) { before = result.getInt(1); }
            assertThat(client.listTools().tools()).extracting(tool -> tool.name())
                    .contains("query_tracker", "describe_tracker", "search_problems").doesNotContain("schedule_problem");
            var filters = Map.of("topicNames", List.of("dynamic programming", "ARRAYS"), "difficulty", "HARD",
                    "statuses", List.of("NOT_STARTED", "ATTEMPTED"), "limit", 1);
            var first = query(client, "PROBLEMS", filters);
            assertThat(first.path("hasMore").asBoolean()).isTrue();
            assertThat(first.path("items").path(0)).isEqualTo(JSON.readTree("""
                    {"leetcodeId":1,"title":"Problem 1","difficulty":"HARD","status":"NOT_STARTED"}
                    """));
            var next = new java.util.HashMap<String, Object>(filters);
            next.put("page", 1);
            assertThat(query(client, "PROBLEMS", next).path("items").path(0).path("leetcodeId").asInt()).isEqualTo(2);
            next.remove("page");
            next.put("excludeScheduled", true);
            assertThat(query(client, "PROBLEMS", next).path("hasMore").asBoolean()).isFalse();
            assertThat(query(client, "PROBLEMS", Map.of("topicNames", List.of("missing"))).path("items").isEmpty()).isTrue();
            assertThat(query(client, "PROBLEMS", Map.of("scheduledFrom", "2026-11-21")).path("returnedCount").asInt()).isEqualTo(1);
            assertThat(query(client, "PROBLEMS", Map.of("solvedFrom", "2026-11-20", "solvedTo", "2026-11-20"))
                    .path("items").path(0).path("leetcodeId").asInt()).isEqualTo(3);
            assertThat(query(client, "PROBLEMS", Map.of("title", "%' OR 1=1 -- _")).path("returnedCount").asInt()).isZero();
            error(client, "MISSING", Map.of(), "INVALID_DOMAIN");
            error(client, "PROBLEMS", "not JSON", "INVALID_QUERY");
            error(client, "PROBLEMS", Map.of("goalType", "DAILY"), "UNSUPPORTED_FILTER");
            error(client, "PROBLEMS", Map.of("difficulty", "TRIVIAL"), "INVALID_VALUE");
            error(client, "PROBLEMS", Map.of("sortBy", "title; DROP TABLE problems"), "INVALID_VALUE");
            error(client, "PROBLEMS", Map.of("solvedFrom", "2026-02-30"), "INVALID_VALUE");
            error(client, "PROBLEMS", Map.of("limit", "5"), "INVALID_VALUE");
            error(client, "PROBLEMS", java.util.Collections.singletonMap("limit", null), "INVALID_VALUE");
            error(client, "PROBLEMS", Map.of("title", "x".repeat(8500)), "INVALID_QUERY");
            error(client, "PROBLEMS", Map.of("limit", 101), "INVALID_VALUE");
            assertThat(json(client, "describe_tracker", Map.of()).path("topicNames").toString()).contains("Dynamic Programming");
            try (var result = statement.executeQuery("PRAGMA data_version")) { assertThat(result.getInt(1)).isEqualTo(before); }
        }
    }

    @Test
    void usesExistingGoalPlannerRevisionAndAnalyticsSemantics() {
        try (var client = connect(port)) {
            var goals = query(client, "GOALS", Map.of("activeOn", "2026-11-20", "type", "WEEKLY",
                    "difficulty", "HARD", "topicName", "dynamic programming", "completed", true));
            assertThat(goals.path("items").size()).isEqualTo(1);
            assertThat(goals.path("items").path(0).path("progress").path("solvedCount").asInt()).isEqualTo(1);
            assertThat(query(client, "GOALS", Map.of("completed", false)).path("returnedCount").asInt()).isEqualTo(1);
            for (String state : List.of("PLANNED", "COMPLETED", "SKIPPED")) {
                var plan = query(client, "PLANNER", Map.of("startDate", "2026-11-20", "endDate", "2026-11-21",
                        "state", state, "difficulty", "HARD"));
                assertThat(plan.path("items").size()).isEqualTo(1);
                assertThat(plan.path("items").path(0).path("state").asString()).isEqualTo(state);
            }
            var revisions = query(client, "REVISIONS", Map.of("dueBefore", "2026-11-21", "status", "SCHEDULED"));
            assertThat(revisions.path("items").size()).isEqualTo(1);
            assertThat(query(client, "REVISIONS", Map.of("startDate", "9999-01-01"))
                    .path("items").path(0).path("timing").asString()).isEqualTo("UPCOMING");
            for (String report : List.of("DASHBOARD_SUMMARY", "TOPIC_PROGRESS", "DIFFICULTY_PROGRESS",
                    "SOLVING_ACTIVITY", "RECENT_PROBLEMS")) {
                assertThat(query(client, "ANALYTICS", Map.of("report", report)).path("items").isArray()).isTrue();
            }
            error(client, "PLANNER", Map.of(), "MISSING_FIELD");
            error(client, "ANALYTICS", Map.of("report", "DASHBOARD_SUMMARY", "range", "7"), "UNSUPPORTED_FILTER");
        }
    }

    @Test
    void listsAndReadsResourcesAndPromptsWithinMeasuredBounds() {
        for (int id = 5; id <= 419; id++) {
            var date = java.time.LocalDate.of(2025, 1, 1).plusDays(id).toString();
            boolean solved = id <= 380;
            problem(id, "HARD", solved ? "SOLVED" : "NOT_STARTED", solved ? date : null);
            jdbc.update("UPDATE problems SET title = ? WHERE id = ?",
                    "Practice problem " + id + " with arrays and dynamic programming", id);
            if (id <= 150) jdbc.update("""
                    INSERT INTO revision_tasks(problem_id,scheduled_date,status,created_at,updated_at)
                    VALUES (?, '2026-11-27','SCHEDULED','2026-11-20T00:00:00Z','2026-11-20T00:00:00Z')
                    """, id);
            jdbc.update("INSERT INTO topics VALUES (?, ?, ?, '2026-11-20T00:00:00Z')", id, "Topic " + id, "topic " + id);
            jdbc.update("INSERT INTO goals(name,type,start_date,end_date,target_count) VALUES (?, 'DAILY','2026-11-20','2026-11-20',1)", "Goal " + id);
        }
        try (var client = connect(port)) {
            assertThat(client.listResources().resources()).hasSize(5);
            for (var resource : client.listResources().resources()) {
                var read = client.readResource(new ReadResourceRequest(resource.uri()));
                var text = ((TextResourceContents) read.contents().getFirst()).text();
                assertThat(JSON.readTree(text).isObject()).isTrue();
                assertThat(text.length()).isLessThan(12000);
                assertThat(text).doesNotContain(".sqlite", "APPROVAL_TOKEN");
            }
            assertThat(client.listPrompts().prompts()).hasSize(5);
            for (var prompt : client.listPrompts().prompts()) {
                var args = prompt.name().equals("create_study_plan")
                        ? Map.<String, Object>of("topic", "Arrays", "difficulty", "HARD") : Map.<String, Object>of();
                var result = client.getPrompt(new GetPromptRequest(prompt.name(), args));
                var text = ((TextContent) result.messages().getFirst().content()).text();
                assertThat(text.length()).isLessThan(1250);
                if (prompt.name().equals("create_study_plan")) assertThat(text).contains("recommend_study_plan", "commit_study_plan", "approval");
            }
            var topics = json(client, "get_topic_progress", Map.of());
            var goals = json(client, "list_goals", Map.of());
            assertThat(json(client, "list_topics", Map.of()).path("topics").size()).isEqualTo(50);
            assertThat(topics.path("topics").size()).isEqualTo(50);
            assertThat(goals.path("goals").size()).isEqualTo(50);
            var activity = json(client, "get_solving_activity", Map.of("range", "all"));
            assertThat(activity.path("days").size()).isEqualTo(365);
            assertThat(activity.path("totalSolvesInRange").asInt()).isEqualTo(377);
            assertThat(activity.path("truncated").asBoolean()).isTrue();
            var revisions = json(client, "get_upcoming_revisions", Map.of("fromDate", "2026-11-27", "limit", 100));
            assertThat(revisions.path("revisions").size()).isEqualTo(100);
            var recommendation = json(client, "recommend_study_plan", Map.of("days", 30, "candidateLimit", 30));
            assertThat(recommendation.path("schedule").size()).isEqualTo(30);
            var compact = query(client, "PROBLEMS", Map.of("limit", 100));
            assertThat(compact.path("returnedCount").asInt()).isEqualTo(100);
            assertThat(compact.toString().length()).isLessThan(20000);
            System.out.printf("MCP test payloads (ASCII bytes): query=%d topics=%d goals=%d activity=%d revisions=%d plan=%d%n",
                    compact.toString().length(), topics.toString().length(), goals.toString().length(),
                    activity.toString().length(), revisions.toString().length(), recommendation.toString().length());
        }
    }

    @Test
    void unifiedProfileKeepsHighLevelWorkflowsWithoutLegacyReadsOrDisabledWrites() {
        try (var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TrackerApplication.class)
                .run("--server.port=0", "--tracker.database-path=" + directory.resolve("unified.sqlite"),
                        "--tracker.backup-root=" + directory.resolve("unified-backups"),
                        "--spring.ai.mcp.server.enabled=true", "--tracker.mcp-tool-profile=UNIFIED");
             var client = connect(app.getWebServer().getPort())) {
            assertThat(client.listTools().tools()).extracting(tool -> tool.name()).containsExactlyInAnyOrder(
                    "query_tracker", "describe_tracker", "ping_tracker", "recommend_study_plan",
                    "search_leetcode_problems", "get_leetcode_problem", "preview_leetcode_import");
            assertThat(query(client, "PROBLEMS", Map.of()).path("returnedCount").asInt()).isZero();
        }
    }

    private void problem(int id, String difficulty, String status, String solved) {
        jdbc.update("""
                INSERT INTO problems VALUES (?,?,?,'fixture','https://leetcode.com/problems/fixture/',?,
                    '2026-11-20T00:00:00Z','2026-11-20T00:00:00Z')
                """, id, id, "Problem " + id, difficulty);
        jdbc.update("""
                INSERT INTO problem_progress VALUES (?,?,?,?,'private notes','2026-11-20T00:00:00Z')
                """, id, status, status.equals("ATTEMPTED") ? "2026-11-19" : null, solved);
    }

    private static McpSyncClient connect(int port) {
        var client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).build())
                .requestTimeout(Duration.ofSeconds(8)).build();
        client.initialize();
        return client;
    }

    private static JsonNode query(McpSyncClient client, String domain, Object filters) {
        return json(client, "query_tracker", Map.of("domain", domain, "query", filters));
    }

    private static JsonNode json(McpSyncClient client, String name, Map<String, Object> args) {
        var result = client.callTool(new CallToolRequest(name, args));
        assertThat(result.isError()).as("%s", result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    private static void error(McpSyncClient client, String domain, Object filters, String code) {
        var result = client.callTool(new CallToolRequest("query_tracker", Map.of("domain", domain, "query", filters)));
        assertThat(result.isError()).isTrue();
        assertThat(JSON.valueToTree(result.structuredContent()).path("code").asString()).isEqualTo(code);
    }
}
