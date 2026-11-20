package dev.dsatracker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ReportingIntegrationTest {
    @TempDir static Path temporaryDirectory;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("reporting.sqlite").toString());
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock reportingTestClock() {
            return Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), ZoneId.of("Asia/Kolkata"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM goals");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
    }

    @Test
    void emptyDatabaseReturnsZeroCountsAndEmptyCollections() throws Exception {
        mvc.perform(get("/api/dashboard/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.solved").value(0))
                .andExpect(jsonPath("$.attempted").value(0))
                .andExpect(jsonPath("$.notStarted").value(0));
        mvc.perform(get("/api/dashboard/today")).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-20"))
                .andExpect(jsonPath("$.plan.counts.planned").value(0))
                .andExpect(jsonPath("$.plan.items").isEmpty())
                .andExpect(jsonPath("$.goals").isEmpty());
        mvc.perform(get("/api/analytics/topics")).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/analytics/difficulty")).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/analytics/activity").param("range", "7")).andExpect(jsonPath("$.days").isEmpty());
        mvc.perform(get("/api/analytics/recent"))
                .andExpect(jsonPath("$.solved").isEmpty())
                .andExpect(jsonPath("$.modified").isEmpty());
    }

    @Test
    void summaryUsesCurrentStatusesAndCountsEachProblemOnce() throws Exception {
        long arrays = topic("Arrays");
        long hashing = topic("Hashing");
        long solved = problem(1, "EASY", arrays, hashing);
        long attempted = problem(2, "MEDIUM", hashing);
        problem(3, "HARD", arrays);
        solve(solved, "2026-11-20");
        progress(attempted, Map.of("status", "ATTEMPTED"));

        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.solved").value(1))
                .andExpect(jsonPath("$.attempted").value(1))
                .andExpect(jsonPath("$.notStarted").value(1));

        progress(solved, Map.of("status", "NOT_STARTED"));
        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(jsonPath("$.solved").value(0))
                .andExpect(jsonPath("$.notStarted").value(2));
    }

    @Test
    void topicAndDifficultyCountsRespectDateRangeAndMultiTopicMembership() throws Exception {
        long arrays = topic("Arrays");
        long hashing = topic("Hashing");
        topic("Unused");
        long current = problem(1, "EASY", arrays, hashing);
        long earlier = problem(2, "HARD", arrays);
        long attempted = problem(3, "MEDIUM", hashing);
        solve(current, "2026-11-14");
        solve(earlier, "2026-11-13");
        progress(attempted, Map.of("status", "ATTEMPTED"));

        mvc.perform(get("/api/analytics/topics").param("range", "7")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Arrays"))
                .andExpect(jsonPath("$[0].total").value(2))
                .andExpect(jsonPath("$[0].solved").value(1))
                .andExpect(jsonPath("$[0].remaining").value(0))
                .andExpect(jsonPath("$[1].name").value("Hashing"))
                .andExpect(jsonPath("$[1].solved").value(1))
                .andExpect(jsonPath("$[1].remaining").value(1))
                .andExpect(jsonPath("$[2].total").value(0));
        mvc.perform(get("/api/analytics/topics").param("range", "all"))
                .andExpect(jsonPath("$[0].solved").value(2));

        mvc.perform(get("/api/analytics/difficulty").param("range", "7"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].difficulty").value("EASY"))
                .andExpect(jsonPath("$[0].solved").value(1));
        mvc.perform(get("/api/v1/analytics/difficulty"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].difficulty").value("HARD"))
                .andExpect(jsonPath("$[1].solved").value(1));
    }

    @Test
    void activityUsesSolvedDatesAndZeroFillsBoundedPeriodsOnlyWhenThereAreSolves() throws Exception {
        long arrays = topic("Arrays");
        solve(problem(1, "EASY", arrays), "2026-11-14");
        solve(problem(2, "EASY", arrays), "2026-11-20");
        solve(problem(3, "MEDIUM", arrays), "2026-11-20");
        solve(problem(4, "MEDIUM", arrays), "2026-11-13");

        mvc.perform(get("/api/analytics/activity").param("range", "7"))
                .andExpect(jsonPath("$.startDate").value("2026-11-14"))
                .andExpect(jsonPath("$.endDate").value("2026-11-20"))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].solved").value(1))
                .andExpect(jsonPath("$.days[1].solved").value(0))
                .andExpect(jsonPath("$.days[6].solved").value(2));
        mvc.perform(get("/api/analytics/activity").param("range", "30"))
                .andExpect(jsonPath("$.startDate").value("2026-10-22"))
                .andExpect(jsonPath("$.days.length()").value(30));
        mvc.perform(get("/api/analytics/activity").param("range", "90"))
                .andExpect(jsonPath("$.startDate").value("2026-08-23"))
                .andExpect(jsonPath("$.days.length()").value(90));
        mvc.perform(get("/api/analytics/activity"))
                .andExpect(jsonPath("$.startDate").doesNotExist())
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].date").value("2026-11-13"));
    }

    @Test
    void handlesMissingSolvedDateAsUndatedCurrentStatus() throws Exception {
        long topic = topic("Arrays");
        long id = problem(1, "EASY", topic);
        // Emulate an older inconsistent row without changing the production schema.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var sql = connection.createStatement()) {
                sql.execute("PRAGMA ignore_check_constraints = ON");
                try (var update = connection.prepareStatement("UPDATE problem_progress SET status = 'SOLVED' WHERE problem_id = ?")) {
                    update.setLong(1, id);
                    update.executeUpdate();
                } finally {
                    sql.execute("PRAGMA ignore_check_constraints = OFF");
                }
            }
            return null;
        });

        mvc.perform(get("/api/dashboard/summary")).andExpect(jsonPath("$.solved").value(1));
        mvc.perform(get("/api/analytics/topics")).andExpect(jsonPath("$[0].solved").value(1));
        mvc.perform(get("/api/analytics/topics").param("range", "7"))
                .andExpect(jsonPath("$[0].solved").value(0))
                .andExpect(jsonPath("$[0].remaining").value(0));
        mvc.perform(get("/api/analytics/difficulty").param("range", "7")).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/analytics/activity")).andExpect(jsonPath("$.days").isEmpty());
        mvc.perform(get("/api/analytics/recent"))
                .andExpect(jsonPath("$.solved").isEmpty())
                .andExpect(jsonPath("$.modified[0].id").value(id))
                .andExpect(jsonPath("$.modified[0].solvedDate").doesNotExist());
    }

    @Test
    void recentListsUseSolvedDateAndLatestCatalogOrProgressModificationInTrackerTime() throws Exception {
        long arrays = topic("Arrays");
        long lower = problem(1, "EASY", arrays);
        long earlier = problem(2, "MEDIUM", arrays);
        long recent = problem(3, "MEDIUM", arrays);
        long nextDay = problem(4, "HARD", arrays);
        solve(lower, "2026-11-14");
        solve(earlier, "2026-11-13");
        solve(recent, "2026-11-20");
        setModified(lower, "2026-11-12T18:30:00.000Z", "2026-11-13T18:30:00.000Z");
        setModified(earlier, "2026-11-13T18:29:59.999Z", "2026-11-13T18:00:00.000Z");
        setModified(recent, "2026-11-19T20:00:00.000Z", "2026-11-19T19:00:00.000Z");
        setModified(nextDay, "2026-11-20T18:30:00.000Z", "2026-11-20T18:30:00.000Z");

        mvc.perform(get("/api/analytics/recent").param("range", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solved.length()").value(2))
                .andExpect(jsonPath("$.solved[0].id").value(recent))
                .andExpect(jsonPath("$.solved[1].id").value(lower))
                .andExpect(jsonPath("$.modified.length()").value(2))
                .andExpect(jsonPath("$.modified[0].id").value(recent))
                .andExpect(jsonPath("$.modified[0].modifiedDate").value("2026-11-20"))
                .andExpect(jsonPath("$.modified[1].id").value(lower))
                .andExpect(jsonPath("$.modified[1].modifiedDate").value("2026-11-14"));
        mvc.perform(get("/api/analytics/recent").param("range", "7").param("limit", "1"))
                .andExpect(jsonPath("$.solved.length()").value(1))
                .andExpect(jsonPath("$.modified.length()").value(1));
    }

    @Test
    void todayComposesExistingPlannerAndActiveGoalsUsingTrackerDate() throws Exception {
        long arrays = topic("Arrays");
        long solved = problem(1, "EASY", arrays);
        long pending = problem(2, "MEDIUM", arrays);
        solve(solved, "2026-11-20");
        postRecord("/api/planner", Map.of("problemId", solved, "date", "2026-11-20"));
        postRecord("/api/planner", Map.of("problemId", pending, "date", "2026-11-21"));
        postRecord("/api/goals", Map.of("name", "Daily", "type", "DAILY", "startDate", "2026-11-20", "targetCount", 1));
        postRecord("/api/goals", Map.of("name", "Week", "type", "WEEKLY", "startDate", "2026-11-16", "targetCount", 3,
                "topicId", arrays));
        postRecord("/api/goals", Map.of("name", "Tomorrow", "type", "DAILY", "startDate", "2026-11-21", "targetCount", 1));
        postRecord("/api/goals", Map.of("name", "Past", "type", "DAILY", "startDate", "2026-11-19", "targetCount", 1));

        mvc.perform(get("/api/v1/dashboard/today")).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-20"))
                .andExpect(jsonPath("$.plan.items.length()").value(1))
                .andExpect(jsonPath("$.plan.items[0].problem.id").value(solved))
                .andExpect(jsonPath("$.plan.items[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.goals.length()").value(2))
                .andExpect(jsonPath("$.goals[0].progress.complete").value(true))
                .andExpect(jsonPath("$.goals[1].progress.solvedCount").value(1))
                .andExpect(jsonPath("$.goals[1].progress.targetCount").value(3));
    }

    @Test
    void invalidReportFiltersUseStructuredErrors() throws Exception {
        mvc.perform(get("/api/analytics/topics").param("range", "31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.range").exists());
        mvc.perform(get("/api/analytics/recent").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.limit").exists());
    }

    private long topic(String name) throws Exception {
        return postRecord("/api/topics", Map.of("name", name));
    }

    private long problem(long number, String difficulty, Long... topics) throws Exception {
        return postRecord("/api/problems", Map.of("leetcodeId", number, "title", "Problem " + number,
                "url", "https://leetcode.com/problems/sample-" + number + "/", "difficulty", difficulty, "topicIds", List.of(topics)));
    }

    private long postRecord(String endpoint, Map<String, ?> input) throws Exception {
        var response = mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asLong();
    }

    private void solve(long id, String date) throws Exception {
        progress(id, Map.of("status", "SOLVED", "solvedDate", date));
    }

    private void progress(long id, Map<String, String> input) throws Exception {
        mvc.perform(patch("/api/problems/{id}/progress", id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isOk());
    }

    private void setModified(long id, String catalogDate, String progressDate) {
        jdbc.update("UPDATE problems SET updated_at = ? WHERE id = ?", catalogDate, id);
        jdbc.update("UPDATE problem_progress SET updated_at = ? WHERE problem_id = ?", progressDate, id);
    }
}
