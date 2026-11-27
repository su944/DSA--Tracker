package dev.dsatracker.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dsatracker.TrackerApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.enabled=true", "tracker.mcp-writes-enabled=true"})
class StudyPlanExecutionIntegrationTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String TOKEN = "a3".repeat(32);
    private static final Instant NOW = Instant.parse("2026-11-19T20:00:00Z");
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);

    @TempDir static Path directory;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock executionClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneId.of("Asia/Kolkata"); }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return TIME.get(); }
            };
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> directory.resolve("execute.sqlite").toString());
        properties.add("tracker.backup-root", () -> directory.resolve("backups").toString());
        properties.add("tracker.study-plan-approval-token", () -> TOKEN);
    }

    @BeforeEach
    void setup() {
        TIME.set(NOW);
        jdbc.update("DELETE FROM study_plan_executions");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
        seed(jdbc);
    }

    @Test
    void requiresAuthenticatedHumanApprovalAndReturnsTheSameFiveDayResultOnRetry() throws Exception {
        try (var client = connect(port); var http = HttpClient.newHttpClient()) {
            assertThat(client.listTools().tools()).filteredOn(tool -> tool.name().contains("approve")).isEmpty();
            assertThat(client.listTools().tools()).anySatisfy(tool -> assertThat(tool.name()).isEqualTo("commit_study_plan"));
            var proposal = proposal(client, Map.of("topicNames", List.of("Arrays"), "difficulty", "HARD"));
            var id = proposal.path("proposalId").asString();
            assertThat(commit(client, id, true).path("status").asString()).isEqualTo("PROPOSED");
            assertThat(operator(http, port, id, "request", null, null).statusCode()).isEqualTo(401);
            assertThat(operator(http, port, id, "request", "incorrect", null).statusCode()).isEqualTo(401);
            assertThat(operator(http, port, id, "request", TOKEN, "http://127.0.0.1").statusCode()).isEqualTo(403);
            assertThat(operator(http, port, id, "approve", TOKEN, null).statusCode()).isEqualTo(404);
            var approval = action(http, port, id, "request");
            assertThat(approval.path("proposal")).isEqualTo(proposal);
            assertThat(approval.path("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(commit(client, id, true).path("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(operator(http, port, id, "approve", null, null).statusCode()).isEqualTo(401);
            assertThat(operator(http, port, id, "approve", TOKEN, "https://example.com").statusCode()).isEqualTo(403);
            assertThat(action(http, port, id, "approve").path("status").asString()).isEqualTo("APPROVED");
            assertThat(call(client, "commit_study_plan", Map.of("proposalId", id, "leetcodeIds", List.of(999))).isError())
                    .isTrue();
            var result = commit(client, id, false);
            assertThat(result.path("status").asString()).isEqualTo("COMMITTED");
            assertThat(result.path("createdCount").asInt()).isEqualTo(5);
            assertThat(result.path("plannerEntries").valueStream().map(item -> item.path("date").asString()).toList())
                    .containsExactly("2026-11-20", "2026-11-21", "2026-11-22", "2026-11-23", "2026-11-24");
            assertThat(count(jdbc)).isEqualTo(5);
            assertThat(commit(client, id, false)).isEqualTo(result);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_progress WHERE status <> 'NOT_STARTED'", Integer.class))
                    .isZero();
        }
    }

    @Test
    void rejectsExpiredRejectedAndUnknownProposals() throws Exception {
        try (var client = connect(port); var http = HttpClient.newHttpClient()) {
            assertThat(call(client, "commit_study_plan", Map.of("proposalId", "not-a-uuid")).isError()).isTrue();
            assertThat(call(client, "commit_study_plan", Map.of("proposalId", UUID.randomUUID().toString())).isError()).isTrue();
            var rejected = request(client, http, Map.of("days", 1));
            assertThat(action(http, port, rejected, "reject").path("status").asString()).isEqualTo("REJECTED");
            assertThat(action(http, port, rejected, "approve").path("status").asString()).isEqualTo("REJECTED");
            assertThat(commit(client, rejected, true).path("status").asString()).isEqualTo("REJECTED");

            var expired = approve(client, http, Map.of("days", 1));
            TIME.set(NOW.plusSeconds(600));
            assertThat(commit(client, expired, true).path("status").asString()).isEqualTo("EXPIRED");
            assertThat(count(jdbc)).isZero();
        }
    }

    @Test
    void revalidatesCurrentProgressTopicsDifficultyAndExistingPlannerEntries() throws Exception {
        try (var client = connect(port); var http = HttpClient.newHttpClient()) {
            var filters = Map.<String, Object>of("topicNames", List.of("Arrays"), "difficulty", "HARD", "days", 1);
            var solved = approve(client, http, filters);
            jdbc.update("UPDATE problem_progress SET status='SOLVED',solved_date='2026-11-20' WHERE problem_id=1");
            assertThat(commit(client, solved, true).path("error").asString()).contains("progress has changed");
            jdbc.update("UPDATE problem_progress SET status='NOT_STARTED',solved_date=NULL WHERE problem_id=1");
            var topic = approve(client, http, filters);
            jdbc.update("DELETE FROM problem_topics WHERE problem_id=1");
            assertThat(commit(client, topic, true).path("error").asString()).contains("topic or difficulty");
            jdbc.update("INSERT INTO problem_topics VALUES (1,1)");
            var difficulty = approve(client, http, filters);
            jdbc.update("UPDATE problems SET difficulty='MEDIUM' WHERE id=1");
            assertThat(commit(client, difficulty, true).path("status").asString()).isEqualTo("FAILED");
            jdbc.update("UPDATE problems SET difficulty='HARD' WHERE id=1");
            var occupied = approve(client, http, filters);
            jdbc.update("INSERT INTO plan_items(problem_id,planned_date) VALUES (1,'2026-11-20')");
            assertThat(commit(client, occupied, true).path("error").asString()).contains("planner assignment");
            assertThat(count(jdbc)).isEqualTo(1);
        }
    }

    @Test
    void rejectsDuplicateProblemsInStoredSnapshotAndRollsBackPartialInsert() throws Exception {
        try (var client = connect(port); var http = HttpClient.newHttpClient()) {
            var duplicate = approve(client, http, Map.of("days", 2));
            jdbc.update("""
                    UPDATE study_plan_executions
                    SET snapshot=json_set(snapshot,'$.assignments[1].problemId',1)
                    WHERE proposal_id=?
                    """, duplicate);
            assertThat(commit(client, duplicate, true).path("error").asString()).contains("repeats a problem");
            var failure = approve(client, http, Map.of("days", 3));
            jdbc.execute("""
                    CREATE TRIGGER reject_second_plan_entry BEFORE INSERT ON plan_items WHEN NEW.problem_id=2
                    BEGIN SELECT RAISE(ABORT, 'Forced partial batch failure'); END
                    """);
            try {
                assertThat(commit(client, failure, true).path("status").asString()).isEqualTo("FAILED");
                assertThat(count(jdbc)).isZero();
                assertThat(commit(client, failure, true).path("status").asString()).isEqualTo("FAILED");
            } finally {
                jdbc.execute("DROP TRIGGER reject_second_plan_entry");
            }
        }
    }

    @Test
    void concurrentCallsCommitOneBatchAndHonorMultipleProblemsPerDay() throws Exception {
        try (var first = connect(port); var second = connect(port); var http = HttpClient.newHttpClient();
             var pool = Executors.newFixedThreadPool(2)) {
            var id = approve(first, http, Map.of("days", 2, "problemsPerDay", 2));
            var gate = new CyclicBarrier(2);
            var one = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return commit(first, id, false); });
            var two = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return commit(second, id, false); });
            var result = one.get(12, TimeUnit.SECONDS);
            assertThat(two.get(12, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(result.path("createdCount").asInt()).isEqualTo(4);
            assertThat(jdbc.queryForList("SELECT count(*) FROM plan_items GROUP BY planned_date ORDER BY planned_date", Integer.class))
                    .containsExactly(2, 2);
        }
    }

    @Test
    void committedResultAndApprovedSnapshotSurviveApplicationRestarts() throws Exception {
        var database = directory.resolve("restart.sqlite");
        String previouslyCommitted;
        String previouslyApproved;
        String previouslyPending;
        JsonNode original;
        try (var app = restart(database); var http = HttpClient.newHttpClient(); var client = connect(app.getWebServer().getPort())) {
            var db = app.getBean(JdbcTemplate.class);
            seed(db);
            int firstPort = app.getWebServer().getPort();
            var first = proposal(client, Map.of("days", 1)).path("proposalId").asString();
            action(http, firstPort, first, "request");
            action(http, firstPort, first, "approve");
            original = commit(client, first, false);
            previouslyCommitted = first;
            previouslyApproved = proposal(client, Map.of("days", 2)).path("proposalId").asString();
            action(http, firstPort, previouslyApproved, "request");
            action(http, firstPort, previouslyApproved, "approve");
            previouslyPending = proposal(client, Map.of("days", 1)).path("proposalId").asString();
            action(http, firstPort, previouslyPending, "request");
        }
        try (var app = restart(database); var client = connect(app.getWebServer().getPort());
             var http = HttpClient.newHttpClient()) {
            assertThat(commit(client, previouslyPending, true).path("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(action(http, app.getWebServer().getPort(), previouslyPending, "reject")
                    .path("status").asString()).isEqualTo("REJECTED");
            assertThat(commit(client, previouslyCommitted, false)).isEqualTo(original);
            assertThat(commit(client, previouslyApproved, false).path("createdCount").asInt()).isEqualTo(2);
            assertThat(count(app.getBean(JdbcTemplate.class))).isEqualTo(3);
        }
    }

    private static ServletWebServerApplicationContext restart(Path database) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(TrackerApplication.class)
                .run("--server.port=0", "--tracker.database-path=" + database,
                        "--tracker.backup-root=" + directory.resolve("restart-backups"),
                        "--spring.ai.mcp.server.enabled=true", "--tracker.mcp-writes-enabled=true",
                        "--tracker.study-plan-approval-token=" + TOKEN);
    }

    private static void seed(JdbcTemplate db) {
        db.update("INSERT INTO topics VALUES (1,'Arrays','arrays',?)", NOW.toString());
        for (int id = 1; id <= 6; id++) {
            db.update("""
                    INSERT INTO problems(id,leetcode_id,title,slug,url,difficulty,created_at,updated_at)
                    VALUES (?,?,'Fixture','fixture','https://leetcode.com/problems/fixture/','HARD',?,?)
                    """, id, id, NOW.toString(), NOW.toString());
            db.update("INSERT INTO problem_progress(problem_id,status,updated_at) VALUES (?,'NOT_STARTED',?)", id, NOW.toString());
            db.update("INSERT INTO problem_topics VALUES (?,1)", id);
        }
    }

    private static int count(JdbcTemplate db) {
        return db.queryForObject("SELECT count(*) FROM plan_items", Integer.class);
    }

    private String request(McpSyncClient client, HttpClient http, Map<String, Object> arguments) throws Exception {
        String id = proposal(client, arguments).path("proposalId").asString();
        assertThat(action(http, port, id, "request").path("status").asString()).isEqualTo("PENDING_APPROVAL");
        return id;
    }

    private String approve(McpSyncClient client, HttpClient http, Map<String, Object> arguments) throws Exception {
        String id = request(client, http, arguments);
        assertThat(action(http, port, id, "approve").path("status").asString()).isEqualTo("APPROVED");
        return id;
    }

    private static HttpResponse<String> operator(HttpClient http, int port, String id, String action, String token,
                                                 String origin) throws Exception {
        var url = "http://127.0.0.1:" + port + "/api/study-plan-approvals/" + id + "/" + action;
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.header("Origin", origin);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode action(HttpClient http, int port, String id, String action) throws Exception {
        var response = operator(http, port, id, action, TOKEN, null);
        assertThat(response.statusCode()).as("%s: %s", action, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static McpSyncClient connect(int port) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(8)).build();
        client.initialize();
        return client;
    }

    private static CallToolResult call(McpSyncClient client, String name, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(name, arguments));
    }

    private static JsonNode proposal(McpSyncClient client, Map<String, Object> arguments) {
        var result = call(client, "recommend_study_plan", arguments);
        assertThat(result.isError()).as("%s", result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    private static JsonNode commit(McpSyncClient client, String id, boolean error) {
        var result = call(client, "commit_study_plan", Map.of("proposalId", id));
        assertThat(result.isError()).as("%s", result.content()).isEqualTo(error);
        return JSON.valueToTree(result.structuredContent());
    }
}
