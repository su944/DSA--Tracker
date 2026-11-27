package dev.dsatracker.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
        properties = {"spring.ai.mcp.server.enabled=true", "tracker.mcp-writes-enabled=false"})
class StudyPlanRecommendationIntegrationTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final Instant NOW = Instant.parse("2026-11-19T20:00:00Z");
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);

    @TempDir static Path temporaryDirectory;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StudyPlanRecommendationService service;

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock recommendationClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneId.of("Asia/Kolkata"); }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return TIME.get(); }
            };
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("tracker.database-path", () -> temporaryDirectory.resolve("plans.sqlite").toString());
        registry.add("tracker.backup-root", () -> temporaryDirectory.resolve("backups").toString());
    }

    @BeforeEach
    void reset() {
        TIME.set(NOW);
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
    }

    @Test
    void recommendsFiveConsecutiveDaysWithCompactDeterministicEntriesAndCorrectEligibleCount() throws Exception {
        jdbc.update("""
                INSERT INTO topics(id,name,name_key,created_at)
                VALUES (1,'Dynamic Programming','dynamic programming','2026-11-19T20:00:00Z'),
                       (2,'Graphs','graphs','2026-11-19T20:00:00Z'),
                       (3,'Other','other','2026-11-19T20:00:00Z')
                """);
        problem(1, "HARD", "SOLVED", 1);
        problem(2, "HARD", "ATTEMPTED", 1);
        problem(3, "EASY", "NOT_STARTED", 1);
        problem(4, "HARD", "NOT_STARTED", 3);
        problem(5, "HARD", "NOT_STARTED", 1);
        schedule(5, "2026-11-01");
        problem(6, "HARD", "NOT_STARTED", 1, 2);
        problem(7, "HARD", "NOT_STARTED", 2);
        problem(8, "HARD", "NOT_STARTED", 1);
        problem(9, "HARD", "NOT_STARTED", 1);
        problem(10, "HARD", "NOT_STARTED", 1);
        try (var observer = DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("plans.sqlite"));
             var client = connect()) {
            int before = version(observer);
            assertThat(client.listTools().tools()).anySatisfy(tool -> {
                assertThat(tool.name()).isEqualTo("recommend_study_plan");
                assertThat(tool.annotations().readOnlyHint()).isTrue();
                assertThat(tool.annotations().openWorldHint()).isFalse();
            });
            var args = Map.<String, Object>of("topicNames", java.util.List.of(" dynamic PROGRAMMING ", "graphs"),
                    "difficulty", "HARD");
            var proposal = recommend(client, args);
            assertThat(proposal.path("startDate").asString()).isEqualTo("2026-11-20");
            assertThat(proposal.path("requestedCount").asInt()).isEqualTo(5);
            assertThat(proposal.path("eligibleCount").asInt()).isEqualTo(6);
            assertThat(proposal.path("selectedCount").asInt()).isEqualTo(5);
            assertThat(proposal.path("insufficientCandidates").asBoolean()).isFalse();
            var schedule = proposal.path("schedule");
            assertThat(schedule.valueStream().map(entry -> entry.path("leetcodeId").asInt()).toList())
                    .containsExactly(6, 7, 8, 9, 10);
            assertThat(schedule.valueStream().map(entry -> entry.path("date").asString()).toList())
                    .containsExactly("2026-11-20", "2026-11-21", "2026-11-22", "2026-11-23", "2026-11-24");
            assertThat(Set.copyOf(schedule.path(0).propertyNames()))
                    .containsExactlyInAnyOrder("date", "leetcodeId", "title", "difficulty", "status");
            assertThat(recommend(client, args).path("schedule")).isEqualTo(schedule);
            assertThat(version(observer)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_items", Integer.class)).isEqualTo(1);
            var id = UUID.fromString(proposal.path("proposalId").asString());
            assertThat(service.findProposal(id).orElseThrow().assignments()).hasSize(5);
            assertThat(proposal.path("expiresAt").asString()).isEqualTo("2026-11-19T20:10:00Z");
            TIME.set(NOW.plusSeconds(600));
            assertThat(service.findProposal(id)).isEmpty();
        }
    }

    @Test
    void allowsSolvedAndScheduledWhenRequestedButAvoidsSameDateCollisions() {
        problem(1, "HARD", "SOLVED");
        problem(2, "HARD", "NOT_STARTED");
        problem(3, "HARD", "NOT_STARTED");
        schedule(3, "2026-11-21");
        problem(4, "HARD", "NOT_STARTED");
        schedule(4, "2026-11-20");
        schedule(4, "2026-11-21");
        try (var client = connect()) {
            var proposal = recommend(client, Map.of("days", 2, "excludeScheduled", false, "excludeSolved", false));
            assertThat(proposal.path("eligibleCount").asInt()).isEqualTo(3);
            assertThat(proposal.path("schedule").valueStream().map(entry -> entry.path("leetcodeId").asInt()).toList())
                    .containsExactly(3, 2);
            var includingSolved = recommend(client, Map.of("days", 2, "problemsPerDay", 2,
                    "excludeScheduled", false, "excludeSolved", false));
            assertThat(includingSolved.path("schedule").valueStream().map(entry -> entry.path("leetcodeId").asInt()).toList())
                    .containsExactly(2, 3, 1);
            assertThat(includingSolved.path("schedule").path(2).path("status").asString()).isEqualTo("SOLVED");
            assertThat(includingSolved.path("insufficientCandidates").asBoolean()).isTrue();
        }
    }

    @Test
    void returnsEmptyAndInsufficientAndEnforcesResponseAndInputLimits() {
        try (var client = connect()) {
            var empty = recommend(client, Map.of());
            assertThat(empty.path("eligibleCount").asInt()).isZero();
            assertThat(empty.path("schedule").isEmpty()).isTrue();
            assertThat(empty.path("insufficientCandidates").asBoolean()).isTrue();
            for (int id = 1; id <= 40; id++) problem(id, "EASY", "NOT_STARTED");
            var capped = recommend(client, Map.of("days", 30, "problemsPerDay", 5, "candidateLimit", 30,
                    "startDate", "2026-12-01"));
            assertThat(capped.path("startDate").asString()).isEqualTo("2026-12-01");
            assertThat(capped.path("requestedCount").asInt()).isEqualTo(150);
            assertThat(capped.path("eligibleCount").asInt()).isEqualTo(40);
            assertThat(capped.path("selectedCount").asInt()).isEqualTo(30);
            assertThat(capped.path("schedule").size()).isEqualTo(30);
            assertThat(capped.path("insufficientCandidates").asBoolean()).isTrue();
            assertThat(recommend(client, Map.of("days", 30)).path("selectedCount").asInt()).isEqualTo(15);
            assertThat(recommend(client, Map.of("topicNames", java.util.List.of("Unknown")))
                    .path("eligibleCount").asInt()).isZero();
            reject(client, Map.of("days", 31), "days must be between 1 and 30");
            reject(client, Map.of("problemsPerDay", 6), "problemsPerDay must be between 1 and 5");
            reject(client, Map.of("candidateLimit", 31), "candidateLimit must be between 1 and 30");
            reject(client, Map.of("days", 0), "days must be between 1 and 30");
            reject(client, Map.of("startDate", "2026-02-30"), "YYYY-MM-DD");
            reject(client, Map.of("startDate", "9999-12-31", "days", 2), "0001 to 9999");
        }
    }

    @Test
    void keepsAtMostThirtyTwoProposals() {
        var first = service.recommend(null, null, LocalDate.of(2026, 11, 20), 1, 1, true, true, 1);
        assertThat(service.findProposal(first.proposalId())).isPresent();
        for (int i = 0; i < 32; i++) service.recommend(null, null, null, 1, 1, true, true, 1);
        assertThat(service.findProposal(first.proposalId())).isEmpty();
    }

    private void problem(int id, String difficulty, String status, int... topics) {
        jdbc.update("""
                INSERT INTO problems(id,leetcode_id,title,slug,url,difficulty,created_at,updated_at)
                VALUES (?,?,'Fixture','fixture','https://leetcode.com/problems/fixture/',?,
                        '2026-11-19T20:00:00Z','2026-11-19T20:00:00Z')
                """, id, id, difficulty);
        jdbc.update("""
                INSERT INTO problem_progress(problem_id,status,attempted_date,solved_date,comments,updated_at)
                VALUES (?,?,?,?,'private notes','2026-11-19T20:00:00Z')
                """, id, status, status.equals("ATTEMPTED") ? "2026-11-19" : null,
                status.equals("SOLVED") ? "2026-11-19" : null);
        for (int topic : topics) {
            jdbc.update("INSERT INTO problem_topics(problem_id,topic_id) VALUES (?,?)", id, topic);
        }
    }

    private void schedule(int id, String date) {
        jdbc.update("INSERT INTO plan_items(problem_id,planned_date) VALUES (?,?)", id, date);
    }

    private static int version(Connection observer) throws SQLException {
        try (var statement = observer.createStatement(); var version = statement.executeQuery("PRAGMA data_version")) {
            return version.getInt(1);
        }
    }

    private McpSyncClient connect() {
        var client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).build())
                .requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private static JsonNode recommend(McpSyncClient client, Map<String, Object> args) {
        var result = client.callTool(new CallToolRequest("recommend_study_plan", args));
        assertThat(result.isError()).as("%s", result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    private static void reject(McpSyncClient client, Map<String, Object> args, String message) {
        var result = client.callTool(new CallToolRequest("recommend_study_plan", args));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).anySatisfy(content -> assertThat(((TextContent) content).text()).contains(message));
    }
}
