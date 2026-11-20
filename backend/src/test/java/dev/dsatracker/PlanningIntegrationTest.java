package dev.dsatracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class PlanningIntegrationTest {
    @TempDir static Path temporaryDirectory;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("planning.sqlite").toString());
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock planningTestClock() {
            return Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), ZoneId.of("Asia/Kolkata"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void resetPlanningData() {
        jdbc.update("DELETE FROM goals");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
    }

    @Test
    void createsReadsEditsAndDeletesGoals() throws Exception {
        long id = createGoal("DAILY", "2026-11-20", null, 2, null);
        mvc.perform(get("/api/v1/goals/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value("2026-11-20"))
                .andExpect(jsonPath("$.progress.remainingCount").value(2));
        long topic = createTopic("Arrays");
        mvc.perform(put("/api/goals/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(goalJson("WEEKLY", "2026-11-16", null, 4, topic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value("2026-11-22"))
                .andExpect(jsonPath("$.topic.name").value("Arrays"))
                .andExpect(jsonPath("$.progress.targetCount").value(4));
        mvc.perform(get("/api/goals")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/goals/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/goals/{id}/progress", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void validatesGoalPeriodsTargetsAndTopicReferences() throws Exception {
        var invalid = List.of(
                goalJson("WEEKLY", "2026-11-17", null, 1, null),
                goalJson("DAILY", "2026-11-20", "2026-11-21", 1, null),
                goalJson("CUSTOM", "2026-11-20", null, 1, null),
                goalJson("CUSTOM", "2026-11-20", "2026-11-19", 1, null),
                goalJson("DAILY", "2026-11-20", null, 0, null),
                goalJson("DAILY", "2026-11-20", null, 1, Long.MAX_VALUE),
                goalJson("DAILY", "2026-02-30", null, 1, null),
                goalJson("DAILY", "0000-01-01", null, 1, null));
        for (String body : invalid) {
            mvc.perform(post("/api/goals").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void progressCountsDistinctCurrentSolvesIncludingBothDateBoundaries() throws Exception {
        long arrays = createTopic("Arrays");
        long graphs = createTopic("Graphs");
        long first = createProblem(1, arrays, graphs);
        long last = createProblem(2, graphs);
        long outside = createProblem(3, arrays);
        long attempted = createProblem(4, arrays);
        solve(first, "2026-11-17");
        solve(last, "2026-11-20");
        solve(outside, "2026-11-16");
        progress(attempted, Map.of("status", "ATTEMPTED"));

        long general = createGoal("CUSTOM", "2026-11-17", "2026-11-20", 2, null);
        long byTopic = createGoal("CUSTOM", "2026-11-17", "2026-11-20", 2, arrays);
        long daily = createGoal("DAILY", "2026-11-20", null, 1, graphs);
        createPlan(first, "2026-11-17");
        createPlan(first, "2026-11-18");

        mvc.perform(get("/api/goals/{id}/progress", general))
                .andExpect(jsonPath("$.solvedCount").value(2))
                .andExpect(jsonPath("$.remainingCount").value(0))
                .andExpect(jsonPath("$.complete").value(true));
        mvc.perform(get("/api/goals/{id}/progress", byTopic))
                .andExpect(jsonPath("$.solvedCount").value(1))
                .andExpect(jsonPath("$.complete").value(false));
        mvc.perform(get("/api/goals/{id}/progress", daily))
                .andExpect(jsonPath("$.solvedCount").value(1));

        mvc.perform(put("/api/problems/{id}", first).contentType(MediaType.APPLICATION_JSON)
                        .content(problemJson(1, graphs))).andExpect(status().isOk());
        mvc.perform(get("/api/goals/{id}/progress", byTopic)).andExpect(jsonPath("$.solvedCount").value(0));
        progress(last, Map.of("status", "NOT_STARTED"));
        mvc.perform(get("/api/goals/{id}/progress", general)).andExpect(jsonPath("$.solvedCount").value(1));
    }

    @Test
    void protectsTopicsReferencedOnlyByGoals() throws Exception {
        long topic = createTopic("Arrays");
        long goal = createGoal("DAILY", "2026-11-20", null, 1, topic);
        mvc.perform(delete("/api/topics/{id}", topic)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.fields.id").value("Remove this topic from its goals before deleting it."));
        mvc.perform(delete("/api/goals/{id}", goal)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/topics/{id}", topic)).andExpect(status().isNoContent());
    }

    @Test
    void dailyAndWeeklyViewsIncludeEmptyDaysAndPeriodCounts() throws Exception {
        long problem = createProblem(1, createTopic("Arrays"));
        createPlan(problem, "2026-11-15");
        createPlan(problem, "2026-11-16");
        createPlan(problem, "2026-11-20");
        createPlan(problem, "2026-11-22");
        createPlan(problem, "2026-11-23");
        solve(problem, "2026-11-20");
        mvc.perform(get("/api/planner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-11-20"))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.counts.planned").value(1))
                .andExpect(jsonPath("$.counts.completed").value(1));
        mvc.perform(get("/api/v1/planner").param("view", "WEEK").param("date", "2026-11-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-11-16"))
                .andExpect(jsonPath("$.endDate").value("2026-11-22"))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].counts.completed").value(0))
                .andExpect(jsonPath("$.days[1].items.length()").value(0))
                .andExpect(jsonPath("$.days[4].items[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.counts.planned").value(3))
                .andExpect(jsonPath("$.counts.completed").value(1));
        mvc.perform(get("/api/planner").param("date", "2026-02-30")).andExpect(status().isBadRequest());
    }

    @Test
    void preventsDuplicatesAndSupportsRescheduleSkipRestoreCarryAndRemove() throws Exception {
        long problem = createProblem(1, createTopic("Arrays"));
        long item = createPlan(problem, "2026-11-20");
        createPlan(problem, "2026-11-21");
        mvc.perform(post("/api/planner").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("problemId", problem, "date", "2026-11-20"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.fields.date").exists());
        mvc.perform(put("/api/planner/{id}", item).contentType(MediaType.APPLICATION_JSON).content(dateJson("2026-11-21")))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/planner/{id}", item).contentType(MediaType.APPLICATION_JSON).content(dateJson("2026-11-19")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value("2026-11-19"));
        skip(item, true).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SKIPPED"));
        mvc.perform(get("/api/planner").param("date", "2026-11-19"))
                .andExpect(jsonPath("$.counts.planned").value(1)).andExpect(jsonPath("$.counts.skipped").value(1));
        skip(item, false).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PLANNED"));
        skip(item, true).andExpect(status().isOk());
        mvc.perform(post("/api/planner/{id}/carry-forward", item).contentType(MediaType.APPLICATION_JSON).content(dateJson("2026-11-18")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/planner/{id}/carry-forward", item).contentType(MediaType.APPLICATION_JSON).content(dateJson("2026-11-22")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(item))
                .andExpect(jsonPath("$.date").value("2026-11-22")).andExpect(jsonPath("$.state").value("PLANNED"));
        mvc.perform(get("/api/planner").param("date", "2026-11-19")).andExpect(jsonPath("$.counts.planned").value(0));
        mvc.perform(delete("/api/planner/{id}", item)).andExpect(status().isNoContent());
        mvc.perform(get("/api/planner/{id}", item)).andExpect(status().isNotFound());
        mvc.perform(get("/api/problems/{id}", problem)).andExpect(jsonPath("$.progress.status").value("NOT_STARTED"));
    }

    @Test
    void reconcilesCompletionAfterSolveCorrectionReopenAndProblemDeletion() throws Exception {
        long problem = createProblem(1, createTopic("Arrays"));
        long thursday = createPlan(problem, "2026-11-19");
        long friday = createPlan(problem, "2026-11-20");
        skip(thursday, true).andExpect(status().isOk());
        solve(problem, "2026-11-20");
        mvc.perform(get("/api/planner/{id}", friday)).andExpect(jsonPath("$.state").value("COMPLETED"));
        skip(friday, true).andExpect(status().isConflict());
        mvc.perform(put("/api/planner/{id}", friday).contentType(MediaType.APPLICATION_JSON).content(dateJson("2026-11-18")))
                .andExpect(status().isConflict());
        solve(problem, "2026-11-19");
        mvc.perform(get("/api/planner/{id}", friday)).andExpect(jsonPath("$.state").value("PLANNED"));
        mvc.perform(get("/api/planner/{id}", thursday)).andExpect(jsonPath("$.state").value("SKIPPED"));
        skip(thursday, false).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
        progress(problem, Map.of("status", "ATTEMPTED"));
        mvc.perform(get("/api/planner/{id}", thursday)).andExpect(jsonPath("$.state").value("PLANNED"));
        mvc.perform(delete("/api/problems/{id}", problem)).andExpect(status().isNoContent());
        mvc.perform(get("/api/planner/{id}", friday)).andExpect(status().isNotFound());
    }

    @Test
    void upgradesV2WithoutRewritingExistingCatalogOrMigrationChecksums() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("planning-upgrade.sqlite");
        var old = Flyway.configure().dataSource(url, "", "").target("2").load();
        assertThat(old.migrate().migrationsExecuted).isEqualTo(2);
        var checksums = Arrays.stream(old.info().applied()).map(migration -> migration.getChecksum()).toList();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO topics(name, name_key, created_at) VALUES ('Arrays','arrays','2026-11-20T00:00:00.000Z')");
        }
        var current = Flyway.configure().dataSource(url, "", "").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(Arrays.stream(current.info().applied()).limit(2).map(migration -> migration.getChecksum()).toList())
                .isEqualTo(checksums);
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement();
             var result = sql.executeQuery("SELECT name FROM topics")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("Arrays");
        }
        assertThat(current.migrate().migrationsExecuted).isZero();
    }

    private long createTopic(String name) throws Exception {
        return id(mvc.perform(post("/api/topics").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name)))));
    }

    private long createProblem(long number, Long... topics) throws Exception {
        return id(mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON).content(problemJson(number, topics))));
    }

    private long createGoal(String type, String start, String end, int target, Long topic) throws Exception {
        return id(mvc.perform(post("/api/goals").contentType(MediaType.APPLICATION_JSON).content(goalJson(type, start, end, target, topic))));
    }

    private long createPlan(long problem, String date) throws Exception {
        return id(mvc.perform(post("/api/planner").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("problemId", problem, "date", date)))));
    }

    private long id(ResultActions response) throws Exception {
        var body = response.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private String goalJson(String type, String start, String end, int target, Long topic) {
        var body = json.createObjectNode().put("name", "Practice goal").put("type", type)
                .put("startDate", start).put("targetCount", target);
        if (end != null) body.put("endDate", end);
        if (topic != null) body.put("topicId", topic);
        return json.writeValueAsString(body);
    }

    private String problemJson(long number, Long... topics) {
        return json.writeValueAsString(Map.of("leetcodeId", number, "title", "Problem " + number,
                "url", "https://leetcode.com/problems/sample-" + number + "/", "difficulty", "EASY", "topicIds", List.of(topics)));
    }

    private String dateJson(String date) {
        return json.writeValueAsString(Map.of("date", date));
    }

    private ResultActions skip(long item, boolean skipped) throws Exception {
        return mvc.perform(patch("/api/planner/{id}/skip", item).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("skipped", skipped))));
    }

    private void solve(long problem, String date) throws Exception {
        progress(problem, Map.of("status", "SOLVED", "solvedDate", date));
    }

    private void progress(long problem, Map<String, String> patch) throws Exception {
        mvc.perform(patch("/api/problems/{id}/progress", problem).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(patch))).andExpect(status().isOk());
    }
}
