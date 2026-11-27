package dev.dsatracker.leetcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.dsatracker.cache.ExpiringCache;
import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.leetcode.LeetCodeDtos.StopReason;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
        properties = {"spring.ai.mcp.server.enabled=true", "tracker.mcp-writes-enabled=true", "tracker.leetcode.max-pages=3",
                "tracker.leetcode.read-timeout=400ms", "tracker.leetcode.preview-ttl=2s"})
class LeetCodeDiscoveryIntegrationTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
    private static final Queue<Reply> REPLIES = new ConcurrentLinkedQueue<>();
    private static final Queue<JsonNode> REQUESTS = new ConcurrentLinkedQueue<>();
    private static final HttpServer REMOTE = mockRemote();

    @TempDir static Path temporaryDirectory;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired LeetCodeCatalogService catalog;
    @Autowired ProblemService problems;
    @Autowired Clock clock;

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        Clock discoveryClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return TIME.get(); }
            };
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("discovery.sqlite").toString());
        properties.add("tracker.backup-root", () -> temporaryDirectory.resolve("backups").toString());
        properties.add("tracker.leetcode.endpoint", () -> endpoint().toString());
    }

    @BeforeEach
    void reset() {
        TIME.set(NOW);
        REQUESTS.clear();
        REPLIES.clear();
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
        insertLocal(101);
    }

    @AfterAll
    static void stopRemote() {
        REMOTE.stop(0);
    }

    @Test
    void discoversToolsAndAppliesCombinedSearchAcrossRemotePagesBeforePaginating() {
        reply(page(8, row("101", "Array Puzzle", "array-puzzle", "EaSy", false, "Array", "Math"),
                row("102", "Premium Puzzle", "premium-puzzle", "EASY", true, "Array"),
                row("LCP 5", "Bad Puzzle", "bad-puzzle", "EASY", false, "Array")));
        reply(page(8, row("101", "Array Puzzle", "array-puzzle", "EASY", false, "Array"),
                row("103", "Hard Puzzle", "hard-puzzle", "HARD", false, "Array"),
                row("104", "Queue Puzzle", "queue-puzzle", "EASY", false, "Queue")));
        var last = page(8, row("105", "Queue Follow-up", "queue-follow-up", "EASY", false, "Queue"),
                row("106", "Second Puzzle", "second-puzzle", "EASY", false, "Queue"));
        reply(last);
        try (var mcp = connect()) {
            assertThat(mcp.listTools().tools()).filteredOn(tool -> List.of("search_leetcode_problems",
                    "get_leetcode_problem", "preview_leetcode_import").contains(tool.name()))
                    .hasSize(3).allSatisfy(tool -> {
                        assertThat(tool.annotations().readOnlyHint()).isTrue();
                        assertThat(tool.annotations().openWorldHint()).isTrue();
                    });
            var filters = Map.<String, Object>of("query", "PUZZLE", "difficulty", "EASY",
                    "topicNames", List.of(" array ", "qUEUE"), "limit", 1);
            var first = call(mcp, "search_leetcode_problems", filters);
            assertThat(first.path("hasMore").asBoolean()).isTrue();
            assertThat(first.path("items").path(0)).isEqualTo(JSON.readTree("""
                    {"leetcodeId":101,"title":"Array Puzzle","slug":"array-puzzle",
                     "url":"https://leetcode.com/problems/array-puzzle/","difficulty":"EASY",
                     "topics":["Array","Math"],"paidOnly":false,"alreadyImported":true}
                    """));
            assertThat(skips()).containsExactly(0, 3);
            REQUESTS.clear();
            REPLIES.clear();
            reply(page(3, row("101", "Array Puzzle", "array-puzzle", "EASY", false, "Array"),
                    row("104", "Queue Puzzle", "queue-puzzle", "EASY", false, "Queue"),
                    row("106", "Second Puzzle", "second-puzzle", "EASY", false, "Queue")));
            var secondPage = new java.util.HashMap<>(filters);
            secondPage.put("page", 1);
            var second = call(mcp, "search_leetcode_problems", secondPage);
            assertThat(ids(second.path("items"))).containsExactly(104L);
            assertThat(second.path("page").asInt()).isEqualTo(1);
            assertThat(second.path("hasMore").asBoolean()).isTrue();
            assertThat(REQUESTS.peek().path("variables").path("limit").asInt()).isEqualTo(100);
            assertThat(REQUESTS.peek().path("operationName").asString()).isEqualTo("problemsetQuestionList");
        }
    }

    @Test
    void resolvesNumericIdAcrossPagesThenUsesCacheWithFreshLocalMembership() {
        reply(page(2, row("201", "First", "first", "MEDIUM", false, "Array")));
        reply(page(2, row("202", "Second", "second", "HARD", true, "Graph")));
        try (var mcp = connect()) {
            var item = call(mcp, "get_leetcode_problem", Map.of("leetcodeId", 202));
            assertThat(item.path("slug").asString()).isEqualTo("second");
            assertThat(item.path("paidOnly").asBoolean()).isTrue();
            assertThat(item.path("alreadyImported").asBoolean()).isFalse();
            insertLocal(202);
            assertThat(call(mcp, "get_leetcode_problem", Map.of("leetcodeId", 202))
                    .path("alreadyImported").asBoolean()).isTrue();
            assertThat(skips()).containsExactly(0, 1);
            reply(page(0));
            reject(mcp, "get_leetcode_problem", Map.of("leetcodeId", 999), "not found in the remote catalog");
        }
    }

    @Test
    void previewsOnlyUniqueMissingRecordsAndExpiresWithoutCommittingWrites() throws Exception {
        reply(page(7, row("101", "Existing", "existing", "EASY", false, "Array"),
                row("9223372036854775808", "Invalid ID", "invalid-id", "EASY", false),
                row("301", "Premium", "premium", "EASY", true, "Array"),
                row("302", "Missing", "missing", "EASY", false, "Array")));
        reply(page(7, row("302", "Duplicate", "duplicate", "EASY", false, "Array"),
                row("303", "Invalid difficulty", "invalid-difficulty", "UNKNOWN", false),
                row("304", "Another", "another", "EASY", false, "Array")));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve("discovery.sqlite"));
             var sql = db.createStatement(); var mcp = connect()) {
            int before;
            try (var version = sql.executeQuery("PRAGMA data_version")) {
                before = version.getInt(1);
            }
            var preview = call(mcp, "preview_leetcode_import",
                    Map.of("count", 2, "topicNames", List.of("ARRAY"), "difficulty", "EASY"));
            assertThat(ids(preview.path("candidates"))).containsExactly(302L, 304L);
            assertThat(preview.path("requestedCount").asInt()).isEqualTo(2);
            assertThat(preview.path("returnedCount").asInt()).isEqualTo(2);
            assertThat(preview.path("scannedCount").asInt()).isEqualTo(7);
            assertThat(preview.path("skippedExisting").asInt()).isEqualTo(1);
            assertThat(preview.path("skippedInvalid").asInt()).isEqualTo(2);
            assertThat(preview.path("stopReason").asString()).isEqualTo("RESULT_LIMIT");
            assertThat(preview.path("expirationTime").asString()).isEqualTo("2026-11-26T12:00:02Z");
            UUID id = UUID.fromString(preview.path("previewId").asString());
            assertThat(catalog.cachedPreview(id)).isPresent();
            TIME.set(NOW.plusSeconds(2));
            assertThat(catalog.cachedPreview(id)).isEmpty();
            try (var version = sql.executeQuery("PRAGMA data_version")) {
                assertThat(version.getInt(1)).isEqualTo(before);
            }
            assertThat(problems.existingLeetcodeIds()).containsExactly(101L);
        }
    }

    @Test
    void distinguishesCatalogExhaustionPageAndTimeBoundsAndValidatesLimits() {
        reply(page(0));
        try (var mcp = connect()) {
            var preview = call(mcp, "preview_leetcode_import", Map.of());
            assertThat(preview.path("requestedCount").asInt()).isEqualTo(100);
            assertThat(preview.path("candidates").isEmpty()).isTrue();
            assertThat(preview.path("stopReason").asString()).isEqualTo("CATALOG_EXHAUSTED");
            for (int id = 401; id <= 403; id++) {
                reply(page(99, row(Integer.toString(id), "Fixture", "fixture", "EASY", false, "Array")));
            }
            var bounded = call(mcp, "preview_leetcode_import", Map.of("count", 500));
            assertThat(bounded.path("stopReason").asString()).isEqualTo("PAGE_LIMIT");
            assertThat(bounded.path("returnedCount").asInt()).isEqualTo(3);
            reject(mcp, "preview_leetcode_import", Map.of("count", 501), "between 1 and 500");
            reject(mcp, "search_leetcode_problems", Map.of("limit", 101), "between 1 and 100");
            reject(mcp, "search_leetcode_problems", Map.of("page", 999), "scan bounds");
            reject(mcp, "get_leetcode_problem", Map.of("leetcodeId", 0), "positive integer");
            reply(page(1, row("411", "Premium", "premium", "HARD", true, "Graph")));
            assertThat(ids(call(mcp, "search_leetcode_problems", Map.of("includePremium", true)).path("items")))
                    .containsExactly(411L);
        }
        var settings = new LeetCodeSettings(endpoint(), Duration.ofMillis(200), Duration.ofMillis(400),
                Duration.ofMillis(560), 3, Duration.ofSeconds(2));
        var deadlineCatalog = new LeetCodeCatalogService(new LeetCodeClient(settings), problems, settings, clock);
        REPLIES.add(new Reply(200, page(100, row("501", "Fixture", "fixture", "EASY", false)), 220, null));
        var timeLimited = deadlineCatalog.preview(2, null, null, false);
        assertThat(timeLimited.returnedCount()).isEqualTo(1);
        assertThat(timeLimited.stopReason()).isEqualTo(StopReason.TIME_LIMIT);
    }

    @Test
    void handlesHttpAndGraphQlFailuresAndBoundsRetries() {
        REPLIES.add(new Reply(503, "{}", 0, null));
        REPLIES.add(new Reply(503, "{}", 0, null));
        try (var mcp = connect()) {
            reject(mcp, "search_leetcode_problems", Map.of(), "HTTP 503");
            assertThat(REQUESTS).hasSize(2);
            reply("{\"errors\":[{\"message\":\"unexpected field\"}]}");
            reject(mcp, "search_leetcode_problems", Map.of(), "unofficial API may have changed");
            reply("{\"data\":{}}");
            reject(mcp, "search_leetcode_problems", Map.of(), "unexpected GraphQL catalog shape");
            for (int i = 0; i < 2; i++) REPLIES.add(new Reply(200, page(0), 800, null));
            reject(mcp, "search_leetcode_problems", Map.of(), "timed out");
            assertThat(REQUESTS).hasSize(6);
        }
    }

    @Test
    void respectsRateLimitCooldownAndEvictsCacheAtCapacity() {
        var settings = new LeetCodeSettings(endpoint(), Duration.ofMillis(200), Duration.ofMillis(400),
                Duration.ofSeconds(3), 3, Duration.ofSeconds(2));
        var remote = new LeetCodeClient(settings);
        REPLIES.add(new Reply(429, "{}", 0, "60"));
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        assertThatThrownBy(() -> remote.fetch(100, 0, deadline)).hasMessageContaining("rate limiting");
        assertThatThrownBy(() -> remote.fetch(100, 0, deadline)).hasMessageContaining("rate limiting");
        assertThat(REQUESTS).hasSize(1);

        var cache = new ExpiringCache<Integer, String>(2, clock);
        cache.put(1, "first", NOW.plusSeconds(2));
        cache.put(2, "second", NOW.plusSeconds(2));
        cache.put(3, "third", NOW.plusSeconds(2));
        assertThat(cache.get(1)).isEmpty();
        assertThat(cache.get(2)).contains("second");
        TIME.set(NOW.plusSeconds(3));
        assertThat(cache.get(3)).isEmpty();
    }

    @Test
    void importsSelectedMetadataReusesTopicsAndPreservesExistingSolvedProgress() {
        jdbc.update("""
                INSERT INTO topics (name, name_key, created_at)
                VALUES ('Array', 'array', '2026-11-26T00:00:00Z')
                """);
        reply(page(3, row("601", "Remote original", "remote-original", "HARD", false, "aRRaY", "Binary Search"),
                row("602", "Remote solved", "remote-solved", "EASY", false, "Unused"),
                row("603", "Unselected", "unselected", "MEDIUM", false, "Array")));
        try (var mcp = connect()) {
            String preview = call(mcp, "preview_leetcode_import", Map.of("count", 3)).path("previewId").asString();

            // Another client adds and solves a candidate after preview creation.
            insertLocal(602);
            jdbc.update("""
                    INSERT INTO problem_progress(problem_id,status,attempted_date,solved_date,comments,updated_at)
                    SELECT id,'SOLVED','2026-11-24','2026-11-25','Keep this','2026-11-26T00:00:00Z'
                    FROM problems WHERE leetcode_id=602
                    """);
            var before = progress(602);
            var selected = Map.<String, Object>of("previewId", preview, "leetcodeIds", List.of(601, 602, 601));
            var first = call(mcp, "import_leetcode_problems", selected);
            assertThat(first).isEqualTo(JSON.readTree("""
                    {"imported":1,"skipped":1,"failed":0,"failures":[]}
                    """));
            var created = call(mcp, "get_problem", Map.of("leetcodeId", 601));
            assertThat(created.path("title").asString()).isEqualTo("Remote original");
            assertThat(created.path("difficulty").asString()).isEqualTo("HARD");
            assertThat(created.path("url").asString()).isEqualTo("https://leetcode.com/problems/remote-original/");
            assertThat(created.path("topics").valueStream().map(JsonNode::asString).toList())
                    .containsExactly("Array", "Binary Search");
            assertThat(created.path("progress").path("status").asString()).isEqualTo("NOT_STARTED");
            assertThat(created.path("progress").path("attemptedDate").isNull()).isTrue();
            assertThat(created.path("progress").path("solvedDate").isNull()).isTrue();
            assertThat(created.path("progress").path("comments").asString()).isEmpty();
            assertThat(progress(602)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT name FROM topics ORDER BY name_key", String.class))
                    .containsExactly("Array", "Binary Search");
            assertThat(problems.existingLeetcodeIds()).containsExactlyInAnyOrder(101L, 601L, 602L);
            assertThat(call(mcp, "import_leetcode_problems", selected)).isEqualTo(JSON.readTree("""
                    {"imported":0,"skipped":2,"failed":0,"failures":[]}
                    """));
            assertThat(REQUESTS).hasSize(1);
        }
    }

    @Test
    void rejectsUnknownExpiredAndInvalidSelectionsBeforeAnyWrites() {
        reply(page(1, row("611", "Candidate", "candidate", "EASY", false, "Array")));
        try (var mcp = connect()) {
            String preview = call(mcp, "preview_leetcode_import", Map.of()).path("previewId").asString();
            reject(mcp, "import_leetcode_problems", Map.of("previewId", "not-a-uuid", "leetcodeIds", List.of(611)), "valid preview UUID");
            reject(mcp, "import_leetcode_problems", Map.of("previewId", UUID.randomUUID().toString(),
                    "leetcodeIds", List.of(611)), "not found or has expired");
            reject(mcp, "import_leetcode_problems", Map.of("previewId", preview, "leetcodeIds", List.of()), "1 and 500");
            reject(mcp, "import_leetcode_problems", Map.of("previewId", preview, "leetcodeIds", List.of(611, 999)),
                    "999 is not a candidate");
            reject(mcp, "import_leetcode_problems", Map.of("previewId", preview, "leetcodeIds", List.of(0)), "positive LeetCode IDs");
            TIME.set(NOW.plusSeconds(2));
            reject(mcp, "import_leetcode_problems", Map.of("previewId", preview, "leetcodeIds", List.of(611)),
                    "not found or has expired");
            assertThat(problems.existingLeetcodeIds()).containsExactly(101L);
            assertThat(REQUESTS).hasSize(1);
        }
    }

    @Test
    void concurrentImportsCreateOneProblemAndOneTopic() throws Exception {
        reply(page(1, row("621", "Concurrent", "concurrent", "MEDIUM", false, "New Topic")));
        try (var first = connect(); var second = connect(); var pool = Executors.newFixedThreadPool(2)) {
            var preview = call(first, "preview_leetcode_import", Map.of()).path("previewId").asString();
            var gate = new CyclicBarrier(2);
            var one = pool.submit(importAtBarrier(first, preview, gate));
            var two = pool.submit(importAtBarrier(second, preview, gate));
            var a = one.get(12, TimeUnit.SECONDS);
            var b = two.get(12, TimeUnit.SECONDS);
            assertThat(a.path("imported").asInt() + b.path("imported").asInt()).isEqualTo(1);
            assertThat(a.path("skipped").asInt() + b.path("skipped").asInt()).isEqualTo(1);
            assertThat(a.path("failed").asInt() + b.path("failed").asInt()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM problems WHERE leetcode_id=621", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM topics WHERE name_key='new topic'", Integer.class)).isEqualTo(1);
            assertThat(REQUESTS).hasSize(1);
        }
    }

    @Test
    void reportsPerProblemFailureAndRollsBackItsTopicCreation() {
        reply(page(3, row("631", "Rejected", "rejected", "HARD", false, "Rolled Back"),
                row("632", "No tags", "no-tags", "MEDIUM", false),
                row("633", "Accepted", "accepted", "EASY", false, "Committed")));
        jdbc.execute("""
                CREATE TRIGGER reject_discovery_problem BEFORE INSERT ON problems WHEN NEW.leetcode_id = 631
                BEGIN SELECT RAISE(ABORT, 'Forced test failure'); END
                """);
        try (var mcp = connect()) {
            String preview = call(mcp, "preview_leetcode_import", Map.of()).path("previewId").asString();
            var result = call(mcp, "import_leetcode_problems",
                    Map.of("previewId", preview, "leetcodeIds", List.of(631, 632, 633)));
            assertThat(result.path("imported").asInt()).isEqualTo(1);
            assertThat(result.path("failed").asInt()).isEqualTo(2);
            assertThat(ids(result.path("failures"))).containsExactly(631L, 632L);
            assertThat(problems.existingLeetcodeIds()).containsExactlyInAnyOrder(101L, 633L);
            assertThat(jdbc.queryForList("SELECT name FROM topics", String.class)).containsExactly("Committed");
        } finally {
            jdbc.execute("DROP TRIGGER reject_discovery_problem");
        }
    }

    private Map<String, Object> progress(long leetcodeId) {
        return jdbc.queryForMap("""
                SELECT p.title, r.status, r.attempted_date, r.solved_date, r.comments, r.updated_at
                FROM problems p JOIN problem_progress r ON r.problem_id=p.id WHERE p.leetcode_id=?
                """, leetcodeId);
    }

    private static Callable<JsonNode> importAtBarrier(McpSyncClient client, String preview, CyclicBarrier gate) {
        return () -> {
            gate.await(5, TimeUnit.SECONDS);
            return call(client, "import_leetcode_problems", Map.of("previewId", preview, "leetcodeIds", List.of(621)));
        };
    }

    private McpSyncClient connect() {
        var client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).build())
                .requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
        return client;
    }

    private static JsonNode call(McpSyncClient client, String name, Map<String, Object> args) {
        var result = client.callTool(new CallToolRequest(name, args));
        assertThat(result.isError()).as("%s: %s", name, result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    private static void reject(McpSyncClient client, String name, Map<String, Object> args, String message) {
        var result = client.callTool(new CallToolRequest(name, args));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).anySatisfy(content -> assertThat(((TextContent) content).text()).contains(message));
    }

    private static List<Long> ids(JsonNode items) {
        return items.valueStream().map(item -> item.path("leetcodeId").asLong()).toList();
    }

    private static List<Integer> skips() {
        return REQUESTS.stream().map(request -> request.path("variables").path("skip").asInt()).toList();
    }

    private void insertLocal(int id) {
        jdbc.update("""
                INSERT INTO problems(leetcode_id, title, slug, url, difficulty, created_at, updated_at)
                VALUES (?, 'Local', 'local', 'https://leetcode.com/problems/local/', 'EASY',
                        '2026-11-26T00:00:00Z', '2026-11-26T00:00:00Z')
                """, id);
    }

    private static void reply(String body) {
        REPLIES.add(new Reply(200, body, 0, null));
    }

    @SafeVarargs
    private static String page(int total, Map<String, Object>... questions) {
        return JSON.writeValueAsString(Map.of("data", Map.of("problemsetQuestionList",
                Map.of("total", total, "questions", Arrays.asList(questions)))));
    }

    private static Map<String, Object> row(String id, String title, String slug, String difficulty,
                                           boolean premium, String... tags) {
        return Map.of("frontendQuestionId", id, "title", title, "titleSlug", slug, "difficulty", difficulty,
                "isPaidOnly", premium, "topicTags", Arrays.stream(tags).map(tag -> Map.of("name", tag)).toList());
    }

    private static URI endpoint() {
        return URI.create("http://127.0.0.1:" + REMOTE.getAddress().getPort() + "/graphql/");
    }

    private static HttpServer mockRemote() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/graphql/", exchange -> {
                try (exchange) {
                    REQUESTS.add(JSON.readTree(exchange.getRequestBody()));
                    var response = REPLIES.poll();
                    if (response == null) response = new Reply(500, "{}", 0, null);
                    Thread.sleep(response.delayMillis());
                    if (response.retryAfter() != null) {
                        exchange.getResponseHeaders().add("Retry-After", response.retryAfter());
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    var body = response.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(response.status(), body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Reply(int status, String body, long delayMillis, String retryAfter) {}
}
