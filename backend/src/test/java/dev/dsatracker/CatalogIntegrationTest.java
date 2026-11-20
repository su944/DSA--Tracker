package dev.dsatracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.sqlite.SQLiteException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogIntegrationTest {
    @TempDir static Path temporaryDirectory;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("catalog.sqlite").toString());
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock catalogTestClock() {
            return Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), ZoneId.of("Asia/Kolkata"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clearCatalog() {
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
    }

    @Test
    void problemCrudKeepsSeparateProgressAndManyToManyTopics() throws Exception {
        long arrays = createTopic("Arrays");
        long hashing = createTopic("Hashing");
        long id = createProblem(1, "Two Sum", "EASY", arrays, hashing);

        mvc.perform(get("/api/v1/problems/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leetcodeId").value(1))
                .andExpect(jsonPath("$.slug").value("two-sum"))
                .andExpect(jsonPath("$.url").value("https://leetcode.com/problems/two-sum/"))
                .andExpect(jsonPath("$.topics[0].name").value("Arrays"))
                .andExpect(jsonPath("$.topics[1].name").value("Hashing"))
                .andExpect(jsonPath("$.progress.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.createdAt").exists());
        mvc.perform(patch("/api/problems/{id}/progress", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ATTEMPTED","comments":"Try a hash map"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/problems/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(1, "Two Sum Revised", "MEDIUM", hashing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Two Sum Revised"))
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].id").value(hashing))
                .andExpect(jsonPath("$.progress.comments").value("Try a hash map"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_progress WHERE problem_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT typeof(attempted_date) FROM problem_progress WHERE problem_id = ?", String.class, id)).isEqualTo("text");

        mvc.perform(delete("/api/problems/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/problems/{id}", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_progress", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_topics", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM topics", Integer.class)).isEqualTo(2);
    }

    @Test
    void topicsAreCaseInsensitiveAndReferencedTopicsCannotBeDeleted() throws Exception {
        long arrays = createTopic("Arrays");
        long graphs = createTopic("Graphs");
        long problem = createProblem(1, "Two Sum", "EASY", arrays);
        mvc.perform(post("/api/topics").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"  aRrAyS  "}
                        """)).andExpect(status().isConflict()).andExpect(jsonPath("$.fields.name").exists());
        mvc.perform(put("/api/topics/{id}", graphs).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Arrays"}
                        """)).andExpect(status().isConflict()).andExpect(jsonPath("$.fields.name").exists());
        mvc.perform(put("/api/v1/topics/{id}", arrays).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Ordered Arrays"}
                        """)).andExpect(status().isOk());
        mvc.perform(get("/api/problems/{id}", problem))
                .andExpect(jsonPath("$.topics[0].name").value("Ordered Arrays"));
        mvc.perform(delete("/api/topics/{id}", arrays)).andExpect(status().isConflict());
        mvc.perform(delete("/api/topics/{id}", graphs)).andExpect(status().isNoContent());
        mvc.perform(get("/api/topics/{id}", graphs)).andExpect(status().isNotFound());
        mvc.perform(get("/api/topics")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchesAndCombinesFiltersWithStablePagination() throws Exception {
        long arrays = createTopic("Arrays");
        long hashing = createTopic("Hashing");
        long graph = createTopic("Graphs");
        long sum = createProblem(1, "Two Sum", "EASY", arrays, hashing);
        createProblem(15, "Three Sum", "MEDIUM", arrays);
        createProblem(12, "Integer to Roman", "MEDIUM", hashing);
        createProblem(2, "Add Two Numbers", "EASY", graph);
        mvc.perform(patch("/api/problems/{id}/progress", sum).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SOLVED\"}")).andExpect(status().isOk());

        mvc.perform(get("/api/problems").param("q", "sum").param("topic", Long.toString(arrays), Long.toString(hashing))
                        .param("difficulty", "EASY").param("status", "SOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sum));
        mvc.perform(get("/api/problems").param("q", "15"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].leetcodeId").value(15));
        mvc.perform(get("/api/problems").param("q", "%"))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/problems").param("topic", Long.toString(arrays), Long.toString(hashing))
                        .param("page", "1").param("size", "1").param("sort", "leetcodeId,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[0].leetcodeId").value(12));
    }

    @Test
    void validatesInputsAndReturnsStructuredErrors() throws Exception {
        long arrays = createTopic("Arrays");
        createProblem(1, "Two Sum", "EASY", arrays);
        long second = createProblem(2, "Add Two Numbers", "MEDIUM", arrays);
        mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(1, "Duplicate", "HARD", arrays)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fields.leetcodeId").exists());
        mvc.perform(put("/api/problems/{id}", second).contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(1, "Duplicate", "HARD", arrays)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(9, "Invalid URL", "EASY", arrays).replace("www.leetcode.com", "notleetcode.com")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.url").exists());
        mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(9, "Invalid difficulty", "IMPOSSIBLE", arrays)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.difficulty").exists());
        mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(9, "Unknown topic", "EASY", Long.MAX_VALUE)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.topicIds").exists());
        mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(9, "", "EASY")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.title").exists())
                .andExpect(jsonPath("$.fields.topicIds").exists());
        mvc.perform(get("/api/problems").param("sort", "unknown,desc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.sort").exists());
        mvc.perform(get("/api/problems").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/problems").param("status", "DONE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/problems/{id}", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/problems/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void progressPreservesDatesAndCommentsAndSupportsDirectSolves() throws Exception {
        long arrays = createTopic("Arrays");
        long id = createProblem(1, "Two Sum", "EASY", arrays);
        updateProgress(id, "{\"status\":\"SOLVED\",\"comments\":\"First pass\"}")
                .andExpect(jsonPath("$.attemptedDate").doesNotExist())
                .andExpect(jsonPath("$.solvedDate").value("2026-11-20"));
        updateProgress(id, "{\"comments\":\"Updated\",\"attemptedDate\":\"2026-11-19\"}")
                .andExpect(jsonPath("$.status").value("SOLVED"))
                .andExpect(jsonPath("$.attemptedDate").value("2026-11-19"))
                .andExpect(jsonPath("$.solvedDate").value("2026-11-20"));
        updateProgress(id, "{\"status\":\"ATTEMPTED\"}")
                .andExpect(jsonPath("$.attemptedDate").value("2026-11-19"))
                .andExpect(jsonPath("$.solvedDate").doesNotExist())
                .andExpect(jsonPath("$.comments").value("Updated"));
        updateProgress(id, "{\"status\":\"NOT_STARTED\"}")
                .andExpect(jsonPath("$.attemptedDate").doesNotExist())
                .andExpect(jsonPath("$.solvedDate").doesNotExist())
                .andExpect(jsonPath("$.comments").value("Updated"));
        updateProgress(id, "{\"status\":\"ATTEMPTED\"}")
                .andExpect(jsonPath("$.attemptedDate").value("2026-11-20"));
        updateProgress(id, "{\"status\":\"SOLVED\",\"attemptedDate\":null,\"comments\":\"\"}")
                .andExpect(jsonPath("$.attemptedDate").doesNotExist())
                .andExpect(jsonPath("$.comments").value(""));
    }

    @Test
    void invalidProgressDoesNotChangeSavedProgress() throws Exception {
        long id = createProblem(1, "Two Sum", "EASY", createTopic("Arrays"));
        updateProgress(id, "{\"status\":\"ATTEMPTED\",\"comments\":\"Kept\"}");
        for (String body : List.of(
                "{\"status\":\"INVALID\"}",
                "{\"status\":null}",
                "{\"attemptedDate\":null}",
                "{\"attemptedDate\":\"2026-02-30\"}",
                "{\"attemptedDate\":\"2026-11-21\"}",
                "{\"status\":\"SOLVED\",\"solvedDate\":\"2026-11-19\"}",
                "{\"status\":\"NOT_STARTED\",\"attemptedDate\":\"2026-11-20\"}",
                "{\"status\":\"ATTEMPTED\",\"solvedDate\":\"2026-11-20\"}")) {
            mvc.perform(patch("/api/problems/{id}/progress", id).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(get("/api/problems/{id}", id))
                .andExpect(jsonPath("$.progress.status").value("ATTEMPTED"))
                .andExpect(jsonPath("$.progress.attemptedDate").value("2026-11-20"))
                .andExpect(jsonPath("$.progress.comments").value("Kept"));
    }

    @Test
    void databaseEnforcesUniqueIdsAndRelationshipConstraints() throws Exception {
        long arrays = createTopic("Arrays");
        long problem = createProblem(1, "Two Sum", "EASY", arrays);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO problems(leetcode_id, title, slug, url, difficulty, created_at, updated_at)
                SELECT leetcode_id, title, slug, url, difficulty, created_at, updated_at FROM problems WHERE id = ?
                """, problem)).hasRootCauseInstanceOf(SQLiteException.class).hasStackTraceContaining("[SQLITE_CONSTRAINT");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO problem_topics(problem_id, topic_id) VALUES (?, ?)", problem, arrays))
                .hasRootCauseInstanceOf(SQLiteException.class).hasStackTraceContaining("[SQLITE_CONSTRAINT");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO problem_topics(problem_id, topic_id) VALUES (?, ?)", problem, Long.MAX_VALUE))
                .hasRootCauseInstanceOf(SQLiteException.class).hasStackTraceContaining("[SQLITE_CONSTRAINT");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM topics WHERE id = ?", arrays))
                .hasRootCauseInstanceOf(SQLiteException.class).hasStackTraceContaining("[SQLITE_CONSTRAINT");
    }

    @Test
    void supportsLocalCorsForProgressUpdates() throws Exception {
        mvc.perform(options("/api/problems/1/progress")
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
    }

    @Test
    void appliesV2ToExistingV1Database() {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade.sqlite");
        Flyway baseline = Flyway.configure().dataSource(url, "", "").target("1").load();
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);
        Integer checksum = baseline.info().current().getChecksum();

        Flyway upgraded = Flyway.configure().dataSource(url, "", "").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(upgraded.info().applied()[0].getChecksum()).isEqualTo(checksum);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }

    private long createTopic(String name) throws Exception {
        var result = mvc.perform(post("/api/topics").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createProblem(long leetcodeId, String title, String difficulty, Long... topicIds) throws Exception {
        var result = mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(leetcodeId, title, difficulty, topicIds)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String problemJson(long leetcodeId, String title, String difficulty, Long... topicIds) {
        return json.writeValueAsString(Map.of("leetcodeId", leetcodeId, "title", title, "difficulty", difficulty,
                "url", "https://www.leetcode.com/problems/two-sum?envType=study-plan", "topicIds", List.of(topicIds)));
    }

    private ResultActions updateProgress(long id, String body) throws Exception {
        return mvc.perform(patch("/api/problems/{id}/progress", id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
