package dev.dsatracker.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.enabled=true", "tracker.mcp-writes-enabled=true"})
class TrackerMcpWriteIntegrationTest {
    private static final String APPROVAL_TOKEN = "df".repeat(32);
    private static final JsonMapper JSON = new JsonMapper();

    @TempDir static Path temporaryDirectory;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock writeTestClock() {
            return Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), ZoneId.of("Asia/Kolkata"));
        }
    }

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry properties) {
        properties.add("tracker.study-plan-approval-token", () -> APPROVAL_TOKEN);
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("writes.sqlite").toString());
        properties.add("tracker.backup-root", () -> temporaryDirectory.resolve("backups").toString());
    }

    @BeforeEach
    void seedProblems() {
        jdbc.update("DELETE FROM goals");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
        jdbc.update("""
                INSERT INTO problems (id, leetcode_id, title, slug, url, difficulty, created_at, updated_at)
                VALUES (11, 862, 'Sample', 'sample', 'https://leetcode.com/problems/sample/', 'HARD',
                        '2025-01-01T00:00:00.000Z', '2025-01-01T00:00:00.000Z')
                """);
        jdbc.update("""
                INSERT INTO problem_progress (problem_id, status, attempted_date, comments, updated_at)
                VALUES (11, 'ATTEMPTED', '2026-11-19', 'Keep these notes', '2025-01-01T00:00:00.000Z')
                """);
    }

    @Test
    void advertisesWritesWithAccurateHintsAndRetainsReadTools() {
        try (var client = connect()) {
            var names = Set.of("update_problem_progress", "schedule_problem", "reschedule_problem",
                    "create_goal", "update_goal", "complete_revision", "import_leetcode_problems");
            var tools = client.listTools().tools();
            assertThat(tools).hasSize(27);
            var writes = tools.stream().filter(tool -> names.contains(tool.name())).toList();
            assertThat(writes).hasSize(7).allSatisfy(tool -> {
                assertThat(tool.annotations().readOnlyHint()).isFalse();
                assertThat(tool.annotations().destructiveHint()).isEqualTo(!Set.of("schedule_problem", "create_goal", "import_leetcode_problems").contains(tool.name()));
                assertThat(tool.annotations().idempotentHint()).isEqualTo(Set.of("reschedule_problem", "update_goal", "import_leetcode_problems").contains(tool.name()));
                assertThat(tool.outputSchema()).isNotEmpty();
            });
            for (var tool : List.of("ping_tracker", "get_problem", "get_dashboard_summary", "get_daily_plan")) {
                assertThat(tools).anySatisfy(entry -> {
                    assertThat(entry.name()).isEqualTo(tool);
                    assertThat(entry.annotations().readOnlyHint()).isTrue();
                });
            }
            assertThat(call(client, "get_problem", Map.of("leetcodeId", 862)).path("id").asInt()).isEqualTo(11);
        }
    }

    @Test
    void updatesProgressWithTrackerLocalDateAndPreservesOmittedFields() throws Exception {
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var update = call(client, "update_problem_progress", Map.of("leetcodeId", 862, "status", "SOLVED"));
            assertThat(update.path("status").asString()).isEqualTo("SOLVED");
            assertThat(update.path("attemptedDate").asString()).isEqualTo("2026-11-19");
            assertThat(update.path("solvedDate").asString()).isEqualTo("2026-11-20");
            assertThat(update.path("comments").asString()).isEqualTo("Keep these notes");
            assertThat(jdbc.queryForObject("SELECT scheduled_date FROM revision_tasks WHERE problem_id = 11", String.class))
                    .isEqualTo("2026-11-27");

            var clear = new HashMap<String, Object>(Map.of("leetcodeId", 862, "status", "SOLVED", "comments", ""));
            clear.put("attemptedDate", null);
            var updated = call(client, "update_problem_progress", clear);
            assertThat(updated.path("attemptedDate").isNull()).isTrue();
            assertThat(updated.path("solvedDate").asString()).isEqualTo("2026-11-20");
            assertThat(updated.path("comments").asString()).isEmpty();

            var rest = rest(http, "GET", "/api/problems/11", null, 200).path("progress");
            assertThat(rest.path("status").asString()).isEqualTo("SOLVED");
            assertThat(rest.path("attemptedDate").isNull()).isTrue();
            assertThat(rest.path("solvedDate").asString()).isEqualTo("2026-11-20");
            assertThat(rest.path("comments").asString()).isEmpty();
            rest(http, "PATCH", "/api/problems/11/progress", "{\"comments\":null}", 400);
            rest(http, "PATCH", "/api/problems/11/progress", "{\"comments\":\"From REST\"}", 200);
            assertThat(call(client, "get_problem", Map.of("leetcodeId", 862)).path("progress").path("comments").asString())
                    .isEqualTo("From REST");
        }
    }

    @Test
    void rejectsInvalidProgressWithoutChangingSavedData() {
        try (var client = connect()) {
            reject(client, "update_problem_progress", Map.of("leetcodeId", 862, "status", "WRONG"), "status");
            reject(client, "update_problem_progress", Map.of("leetcodeId", 862), "status");
            reject(client, "update_problem_progress",
                    Map.of("leetcodeId", 862, "status", "SOLVED", "solvedDate", "2026-02-30"), "YYYY-MM-DD");
            reject(client, "update_problem_progress",
                    Map.of("leetcodeId", 862, "status", "SOLVED", "solvedDate", "2026-11-21"), "no later than today");
            reject(client, "update_problem_progress",
                    Map.of("leetcodeId", 862, "status", "SOLVED", "solvedDate", "2026-11-18"), "on or after attempted date");
            reject(client, "update_problem_progress",
                    Map.of("leetcodeId", 404, "status", "SOLVED"), "No problem found with LeetCode ID 404");
            var explicitNull = new HashMap<String, Object>(Map.of("leetcodeId", 862, "status", "SOLVED"));
            explicitNull.put("solvedDate", null);
            reject(client, "update_problem_progress", explicitNull, "A date is required");
            var nullComment = new HashMap<String, Object>(Map.of("leetcodeId", 862, "status", "ATTEMPTED"));
            nullComment.put("comments", null);
            reject(client, "update_problem_progress", nullComment, "comments");
            assertAttemptedUnchanged();
        }
    }

    @Test
    void schedulesAndReschedulesViaExistingPlannerRules() throws Exception {
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var first = call(client, "schedule_problem", Map.of("leetcodeId", 862, "date", "2026-11-20"));
            long entryId = first.path("plannerEntryId").asLong();
            assertThat(first.path("problemId").asInt()).isEqualTo(11);
            assertThat(first.path("scheduledDate").asString()).isEqualTo("2026-11-20");
            assertThat(first.path("completed").asBoolean()).isFalse();
            assertThat(first.path("skipped").asBoolean()).isFalse();
            reject(client, "schedule_problem", Map.of("leetcodeId", 862, "date", "2026-11-20"), "already scheduled");
            reject(client, "schedule_problem", Map.of("leetcodeId", 404, "date", "2026-11-20"), "No problem found");
            reject(client, "schedule_problem", Map.of("leetcodeId", 862, "date", "2026-2-01"), "YYYY-MM-DD");
            reject(client, "reschedule_problem", Map.of("plannerEntryId", entryId, "date", "2026-02-30"), "YYYY-MM-DD");
            reject(client, "reschedule_problem", Map.of("plannerEntryId", 9999, "date", "2026-11-21"), "Plan item not found");
            call(client, "schedule_problem", Map.of("leetcodeId", 862, "date", "2026-11-22"));
            reject(client, "reschedule_problem",
                    Map.of("plannerEntryId", entryId, "date", "2026-11-22"), "already scheduled");
            assertThat(rest(http, "GET", "/api/planner/" + entryId, null, 200).path("date").asString())
                    .isEqualTo("2026-11-20");

            rest(http, "PATCH", "/api/planner/" + entryId + "/skip", "{\"skipped\":true}", 200);
            var moved = call(client, "reschedule_problem", Map.of("plannerEntryId", entryId, "date", "2026-11-21"));
            assertThat(moved.path("skipped").asBoolean()).isTrue();
            assertThat(moved.path("scheduledDate").asString()).isEqualTo("2026-11-21");
            assertThat(rest(http, "GET", "/api/planner/" + entryId, null, 200).path("state").asString())
                    .isEqualTo("SKIPPED");

            var today = call(client, "schedule_problem", Map.of("leetcodeId", 862, "date", "2026-11-20"));
            call(client, "update_problem_progress", Map.of("leetcodeId", 862, "status", "SOLVED"));
            long completedId = today.path("plannerEntryId").asLong();
            assertThat(call(client, "reschedule_problem", Map.of("plannerEntryId", completedId, "date", "2026-11-20"))
                    .path("completed").asBoolean()).isTrue();
            reject(client, "reschedule_problem",
                    Map.of("plannerEntryId", completedId, "date", "2026-11-23"), "Completed tasks cannot be moved");
            assertThat(rest(http, "GET", "/api/planner/" + completedId, null, 200).path("date").asString())
                    .isEqualTo("2026-11-20");
        }
    }

    @Test
    void rollsBackProgressWhenRevisionReconciliationFails() {
        jdbc.execute("""
                CREATE TRIGGER reject_revision_for_test BEFORE INSERT ON revision_tasks
                BEGIN SELECT RAISE(ABORT, 'Forced revision failure'); END
                """);
        try (var client = connect()) {
            reject(client, "update_problem_progress", Map.of("leetcodeId", 862, "status", "SOLVED", "comments", "New"),
                    "could not be saved");
            assertAttemptedUnchanged();
        } finally {
            jdbc.execute("DROP TRIGGER reject_revision_for_test");
        }
    }

    @Test
    void createsGoalsAndCountsDistinctCurrentSolvesByInclusiveDatesAndRestrictions() {
        seedGoalProblems();
        try (var client = connect()) {
            var topic = call(client, "create_goal", goalArgs(Map.of("topicName", "aRrAy")));
            var difficulty = call(client, "create_goal", goalArgs(Map.of("difficulty", "HARD")));
            var both = call(client, "create_goal", goalArgs(Map.of("difficulty", "HARD", "topicName", "Array")));
            var all = call(client, "create_goal", goalArgs(Map.of()));
            assertThat(topic.path("solvedCount").asInt()).isEqualTo(2);
            assertThat(topic.path("topicName").asString()).isEqualTo("Array");
            assertThat(difficulty.path("solvedCount").asInt()).isEqualTo(2);
            assertThat(difficulty.path("topicId").isNull()).isTrue();
            assertThat(both.path("solvedCount").asInt()).isEqualTo(1);
            assertThat(both.path("difficulty").asString()).isEqualTo("HARD");
            assertThat(both.path("remainingCount").asInt()).isEqualTo(3);
            assertThat(all.path("solvedCount").asInt()).isEqualTo(3);
            assertThat(all.path("difficulty").isNull()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_items", Integer.class)).isZero();
        }
    }

    @Test
    void validatesGoalWritesAndKeepsPeriodTypeRules() {
        seedGoalProblems();
        try (var client = connect()) {
            reject(client, "create_goal", goalArgs(Map.of("targetCount", 0)), "positive");
            reject(client, "create_goal", goalArgs(Map.of("topicName", "unknown")), "No topic found");
            reject(client, "create_goal", goalArgs(Map.of("difficulty", "EASIER")), "difficulty");
            reject(client, "create_goal", goalArgs(Map.of("startDate", "2026-02-30")), "YYYY-MM-DD");
            reject(client, "create_goal", goalArgs(Map.of("endDate", "2026-11-18")), "on or after");
            reject(client, "create_goal", Map.of("name", "Test", "type", "CUSTOM",
                    "startDate", "2026-11-19", "targetCount", 1), "Choose an end date");
            reject(client, "create_goal", Map.of("name", "Test", "type", "WEEKLY",
                    "startDate", "2026-11-19", "targetCount", 1), "starts on Monday");
            reject(client, "update_goal", Map.of("goalId", 999, "name", "Missing"), "Goal not found");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM goals", Integer.class)).isZero();
            long daily = call(client, "create_goal", Map.of("name", "Daily", "type", "DAILY",
                    "startDate", "2026-11-19", "targetCount", 1)).path("goalId").asLong();
            assertThat(call(client, "update_goal", Map.of("goalId", daily, "startDate", "2026-11-20"))
                    .path("endDate").asString()).isEqualTo("2026-11-20");
        }
    }

    @Test
    void mergesGoalPatchesAndAllowsExplicitNullRestrictionsAcrossRestAndMcp() throws Exception {
        seedGoalProblems();
        try (var client = connect(); var http = HttpClient.newHttpClient()) {
            var created = call(client, "create_goal", goalArgs(Map.of("difficulty", "HARD", "topicName", "Array")));
            long id = created.path("goalId").asLong();
            var updated = call(client, "update_goal", Map.of("goalId", id, "name", "Renamed", "targetCount", 5));
            assertThat(updated.path("name").asString()).isEqualTo("Renamed");
            assertThat(updated.path("startDate").asString()).isEqualTo("2026-11-19");
            assertThat(updated.path("endDate").asString()).isEqualTo("2026-11-20");
            assertThat(updated.path("topicId").asInt()).isEqualTo(1);
            assertThat(updated.path("difficulty").asString()).isEqualTo("HARD");
            assertThat(updated.path("targetCount").asInt()).isEqualTo(5);
            var legacyBody = """
                    {"name":"REST edit","type":"CUSTOM","startDate":"2026-11-19","endDate":"2026-11-20",
                     "targetCount":5,"topicId":1}
                    """;
            assertThat(rest(http, "PUT", "/api/goals/" + id, legacyBody, 200)
                    .path("difficulty").asString()).isEqualTo("HARD");
            var clear = new HashMap<String, Object>(Map.of("goalId", id));
            clear.put("topicName", null);
            clear.put("difficulty", null);
            var cleared = call(client, "update_goal", clear);
            assertThat(cleared.path("topicId").isNull()).isTrue();
            assertThat(cleared.path("difficulty").isNull()).isTrue();
            assertThat(cleared.path("solvedCount").asInt()).isEqualTo(3);
            var createRest = rest(http, "POST", "/api/goals", legacyBody.replace("\"topicId\":1", "\"difficulty\":\"HARD\""), 201);
            assertThat(createRest.path("progress").path("solvedCount").asInt()).isEqualTo(2);
            assertThat(rest(http, "PUT", "/api/goals/" + createRest.path("id").asLong(),
                    legacyBody.replace("\"topicId\":1", "\"difficulty\":null"), 200)
                    .path("difficulty").isNull()).isTrue();
        }
    }

    @Test
    void completesEarliestRevisionUsingReviewedDateAndPreservesProgress() {
        try (var client = connect()) {
            call(client, "update_problem_progress", Map.of("leetcodeId", 862, "status", "SOLVED", "solvedDate", "2026-11-19"));
            jdbc.update("""
                    INSERT INTO revision_tasks (problem_id, scheduled_date, status, created_at, updated_at)
                    VALUES (11, '2026-11-25', 'SCHEDULED', '2026-11-19T20:00:00.000Z', '2026-11-19T20:00:00.000Z')
                    """);
            var before = call(client, "get_problem", Map.of("leetcodeId", 862)).path("progress");
            reject(client, "complete_revision", Map.of("leetcodeId", 862, "reviewedDate", "2026-11-21"), "later than today");
            reject(client, "complete_revision", Map.of("leetcodeId", 862, "reviewedDate", "2026-02-30"), "YYYY-MM-DD");
            var review = call(client, "complete_revision", Map.of("leetcodeId", 862, "reviewedDate", "2026-11-19"));
            assertThat(review.path("status").asString()).isEqualTo("COMPLETED");
            assertThat(review.path("scheduledDate").asString()).isEqualTo("2026-11-25");
            assertThat(review.path("completedAt").asString()).isEqualTo("2026-11-18T18:30:00Z");
            assertThat(review.path("nextRevisionDate").asString()).isEqualTo("2026-11-26");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM revision_tasks WHERE status = 'SCHEDULED'", Integer.class))
                    .isEqualTo(1);
            var defaultReview = call(client, "complete_revision", Map.of("leetcodeId", 862));
            assertThat(defaultReview.path("nextRevisionDate").asString()).isEqualTo("2026-11-27");
            assertThat(call(client, "get_problem", Map.of("leetcodeId", 862)).path("progress")).isEqualTo(before);
        }
    }

    @Test
    void rejectsRevisionCompletionWithoutAnActiveSchedule() {
        try (var client = connect()) {
            reject(client, "complete_revision", Map.of("leetcodeId", 862), "No active revision found");
            reject(client, "complete_revision", Map.of("leetcodeId", 999), "No problem found");
            assertAttemptedUnchanged();
        }
    }

    private static Map<String, Object> goalArgs(Map<String, ?> overrides) {
        var arguments = new HashMap<String, Object>(Map.of("name", "Goal", "type", "CUSTOM",
                "startDate", "2026-11-19", "endDate", "2026-11-20", "targetCount", 4));
        arguments.putAll(overrides);
        return arguments;
    }

    private void seedGoalProblems() {
        jdbc.update("""
                INSERT INTO topics (id, name, name_key, created_at)
                VALUES (1, 'Array', 'array', '2026-11-19T20:00:00.000Z'),
                       (2, 'Other', 'other', '2026-11-19T20:00:00.000Z')
                """);
        jdbc.update("UPDATE problem_progress SET status = 'SOLVED', solved_date = '2026-11-19' WHERE problem_id = 11");
        for (int id = 12; id <= 15; id++) {
            jdbc.update("""
                    INSERT INTO problems(id, leetcode_id, title, slug, url, difficulty, created_at, updated_at)
                    VALUES (?, ?, 'Sample', 'sample', 'https://leetcode.com/problems/sample/', ?,
                            '2026-11-19T20:00:00.000Z', '2026-11-19T20:00:00.000Z')
                    """, id, id, id == 12 ? "EASY" : "HARD");
            jdbc.update("""
                    INSERT INTO problem_progress(problem_id, status, solved_date, updated_at)
                    VALUES (?, ?, ?, '2026-11-19T20:00:00.000Z')
                    """, id, id == 15 ? "NOT_STARTED" : "SOLVED",
                    id == 15 ? null : id == 14 ? "2026-11-18" : "2026-11-20");
        }
        jdbc.update("INSERT INTO problem_topics (problem_id, topic_id) VALUES (11, 1), (11, 2), (12, 1), (13, 2), (14, 1), (15, 1)");
    }

    private void assertAttemptedUnchanged() {
        assertThat(jdbc.queryForMap("SELECT status, attempted_date, comments, updated_at FROM problem_progress WHERE problem_id = 11"))
                .containsEntry("status", "ATTEMPTED").containsEntry("attempted_date", "2026-11-19")
                .containsEntry("comments", "Keep these notes").containsEntry("updated_at", "2025-01-01T00:00:00.000Z");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM revision_tasks", Integer.class)).isZero();
    }

    private McpSyncClient connect() {
        var client = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl()).build())
                .requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private JsonNode call(McpSyncClient client, String tool, Map<String, Object> args) {
        authorizePlannerAction(tool, args);
        var result = client.callTool(new CallToolRequest(tool, args));
        assertThat(result.isError()).as("%s: %s", tool, result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    private void reject(McpSyncClient client, String tool, Map<String, Object> args, String message) {
        authorizePlannerAction(tool, args);
        var result = client.callTool(new CallToolRequest(tool, args));
        assertThat(result.isError()).as("%s: %s", tool, result.content()).isTrue();
        assertThat(result.content()).anySatisfy(content -> {
            assertThat(content).isInstanceOf(TextContent.class);
            assertThat(((TextContent) content).text()).contains(message);
        });
    }

    private JsonNode rest(HttpClient http, String method, String path, String body, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(5));
        var response = http.send(body == null ? request.GET().build() : request
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    @Test
    void plannerWritesCannotSelfApproveAndGrantsAreExactAndSingleUse() throws Exception {
        try (var client = connect()) {
            var args = Map.<String, Object>of("leetcodeId", 862, "date", "2026-11-20");
            assertThat(client.callTool(new CallToolRequest("schedule_problem", args)).isError()).isTrue();
            authorizePlannerAction("schedule_problem", args);
            assertThat(client.callTool(new CallToolRequest("schedule_problem",
                    Map.of("leetcodeId", 862, "date", "2026-11-21"))).isError()).isTrue();
            assertThat(client.callTool(new CallToolRequest("schedule_problem", args)).isError()).isFalse();
            var replay = client.callTool(new CallToolRequest("schedule_problem", args));
            assertThat(replay.isError()).isTrue();
            assertThat(replay.content().toString()).contains("operator approval");
        }
    }

    @Test
    void storageFailuresDoNotExposeSqlOrDatabasePaths() {
        jdbc.execute("""
                CREATE TRIGGER fail_direct_schedule BEFORE INSERT ON plan_items
                BEGIN SELECT RAISE(ABORT, 'Failure at /private/tmp/tracker.sqlite'); END
                """);
        try (var client = connect()) {
            var args = Map.<String, Object>of("leetcodeId", 862, "date", "2026-11-20");
            authorizePlannerAction("schedule_problem", args);
            var result = client.callTool(new CallToolRequest("schedule_problem", args));
            assertThat(result.isError()).isTrue();
            assertThat(result.content().toString()).doesNotContain("INSERT", "tracker.sqlite", "/private/tmp")
                    .contains("Check the tracker server logs");
        } finally {
            jdbc.execute("DROP TRIGGER fail_direct_schedule");
        }
    }

    private void authorizePlannerAction(String tool, Map<String, Object> args) {
        boolean schedule = "schedule_problem".equals(tool);
        if (!schedule && !"reschedule_problem".equals(tool)) return;
        String date = (String) args.get("date");
        try {
            java.time.LocalDate.parse(date);
        } catch (java.time.DateTimeException ignored) {
            return;
        }
        var id = args.get(schedule ? "leetcodeId" : "plannerEntryId");
        var operation = schedule ? "SCHEDULE" : "RESCHEDULE";
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/planner-write-approvals/"
                            + operation + "/" + id + "/" + date))
                    .header("Authorization", "Bearer " + APPROVAL_TOKEN)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        } catch (java.io.IOException | InterruptedException exception) {
            throw new AssertionError(exception);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
