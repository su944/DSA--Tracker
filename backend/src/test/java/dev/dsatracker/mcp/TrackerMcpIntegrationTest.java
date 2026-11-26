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
import java.time.Duration;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.mcp.server.enabled=true")
class TrackerMcpIntegrationTest {

    private static final JsonMapper JSON = new JsonMapper();

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
                    .containsExactlyInAnyOrder("ping_tracker", "search_problems", "get_problem", "list_topics");
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
            assertThat(dataVersion(observer)).isEqualTo(originalVersion);
        }
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
