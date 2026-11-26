package dev.dsatracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dsatracker.backup.DatabaseBackups;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceIntegrationTest {
    @TempDir static Path temporaryDirectory;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), ZoneId.of("Asia/Kolkata"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("maintenance.sqlite").toString());
        properties.add("tracker.backup-root", () -> temporaryDirectory.resolve("backups").toString());
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock maintenanceTestClock() { return CLOCK; }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DatabaseBackups backups;

    @BeforeEach
    void reset() throws IOException {
        backups.cancel();
        jdbc.update("DELETE FROM goals");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM topics");
    }

    @Test
    void revisionsClassifyDatesAndEnforceActiveUniquenessWithoutChangingOriginalSolve() throws Exception {
        long problem = problem(1, topic("Arrays"));
        revision(problem, "2026-11-20").andExpect(status().isConflict());
        progress(problem, Map.of("status", "SOLVED", "solvedDate", "2026-11-18"));
        long due = id(revision(problem, "2026-11-20"));
        long overdue = id(revision(problem, "2026-11-19"));
        revision(problem, "2026-11-20").andExpect(status().isConflict()).andExpect(jsonPath("$.fields.date").exists());
        mvc.perform(get("/api/v1/revisions").param("filter", "DUE_TODAY"))
                .andExpect(jsonPath("$.today").value("2026-11-20"))
                .andExpect(jsonPath("$.counts.dueToday").value(1))
                .andExpect(jsonPath("$.items[0].id").value(due));
        mvc.perform(get("/api/revisions").param("filter", "OVERDUE"))
                .andExpect(jsonPath("$.items[0].id").value(overdue));
        mvc.perform(get("/api/revisions").param("filter", "UPCOMING"))
                .andExpect(jsonPath("$.items[0].scheduledDate").value("2026-11-25"));
        mvc.perform(get("/api/dashboard/today")).andExpect(jsonPath("$.revisions.overdue").value(1));
        mvc.perform(put("/api/revisions/{id}", due).contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-11-19\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/revisions/{id}", due).contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-11-21\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scheduledDate").value("2026-11-21"));
        mvc.perform(post("/api/revisions/{id}/complete", due)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.completedAt").exists());
        mvc.perform(post("/api/revisions/{id}/complete", due)).andExpect(status().isOk());
        mvc.perform(post("/api/revisions/{id}/cancel", overdue)).andExpect(jsonPath("$.status").value("CANCELLED"));
        revision(problem, "2026-11-19").andExpect(status().isCreated());
        mvc.perform(get("/api/revisions").param("filter", "UPCOMING"))
                .andExpect(jsonPath("$.items[1].scheduledDate").value("2026-11-27"));
        mvc.perform(get("/api/problems/{id}", problem)).andExpect(jsonPath("$.progress.solvedDate").value("2026-11-18"));
    }

    @Test
    void correctsInitialRevisionAndClosesActiveTasksWhenProblemReopens() throws Exception {
        long problem = problem(1, topic("Arrays"));
        progress(problem, Map.of("status", "SOLVED", "solvedDate", "2026-11-17"));
        long manual = id(revision(problem, "2026-11-29"));
        progress(problem, Map.of("solvedDate", "2026-11-18"));
        mvc.perform(get("/api/revisions").param("filter", "UPCOMING"))
                .andExpect(jsonPath("$.items[0].scheduledDate").value("2026-11-25"))
                .andExpect(jsonPath("$.items[1].id").value(manual));
        progress(problem, Map.of("status", "ATTEMPTED"));
        mvc.perform(get("/api/revisions").param("filter", "UPCOMING")).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/revisions/{id}", manual)).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CSV", "JSON"})
    void exportsAndReimportsTopicsDatesAndMultilineCommentsWithoutDuplicates(String format) throws Exception {
        long id = problem(1, topic("Arrays; Tier 1"), topic("Path\\ Graphs"));
        String notes = "Line one\nLine two, with \"quotes\"";
        progress(id, Map.of("status", "SOLVED", "attemptedDate", "2026-11-17", "solvedDate", "2026-11-18", "comments", notes));
        var export = mvc.perform(get("/api/exchange/export").param("format", format)).andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition")).andReturn().getResponse().getContentAsByteArray();

        upload("preview", format, export, "SKIP_EXISTING").andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(delete("/api/problems/{id}", id)).andExpect(status().isNoContent());
        upload("import", format, export, "SKIP_EXISTING").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(0));
        upload("import", format, export, "SKIP_EXISTING").andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(get("/api/problems")).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].topics[0].name").value("Arrays; Tier 1"))
                .andExpect(jsonPath("$.items[0].topics[1].name").value("Path\\ Graphs"))
                .andExpect(jsonPath("$.items[0].progress.attemptedDate").value("2026-11-17"))
                .andExpect(jsonPath("$.items[0].progress.solvedDate").value("2026-11-18"))
                .andExpect(jsonPath("$.items[0].progress.comments").value(notes));
    }

    @Test
    void previewFlagsBadRowsAndDuplicateIdsAndImportUpdatesOnlyCatalogFields() throws Exception {
        long id = problem(1, topic("Arrays"));
        progress(id, Map.of("status", "ATTEMPTED", "comments", "Personal notes"));
        byte[] csv = """
                leetcodeId,title,url,difficulty,topics,status,solvedDate,comments
                1,Renamed,https://leetcode.com/problems/renamed/,HARD,Strings,SOLVED,2026-11-18,Imported notes
                2,Duplicate,https://leetcode.com/problems/hello/,EASY,Arrays,,,
                2,Duplicate again,https://leetcode.com/problems/hello/,EASY,Arrays,,,
                3,Bad,https://example.test/problems/bad/,VERY_HARD,Arrays,SOLVED,2026-02-30,
                4,New,https://leetcode.com/problems/new/,MEDIUM,Arrays,,,
                """.getBytes(StandardCharsets.UTF_8);
        upload("preview", "CSV", csv, "UPDATE_EXISTING").andExpect(status().isOk())
                .andExpect(jsonPath("$.updates").value(1)).andExpect(jsonPath("$.creates").value(1))
                .andExpect(jsonPath("$.invalid").value(3)).andExpect(jsonPath("$.rows[1].errors.leetcodeId").exists())
                .andExpect(jsonPath("$.rows[3].errors.solvedDate").exists())
                .andExpect(jsonPath("$.rows[3].errors.url").exists())
                .andExpect(jsonPath("$.rows[3].errors.difficulty").exists());
        mvc.perform(get("/api/problems/{id}", id)).andExpect(jsonPath("$.title").value("Problem 1"));
        upload("import", "CSV", csv, "UPDATE_EXISTING").andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.failed").value(3));
        mvc.perform(get("/api/problems/{id}", id)).andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.topics[0].name").value("Strings"))
                .andExpect(jsonPath("$.progress.status").value("ATTEMPTED"))
                .andExpect(jsonPath("$.progress.comments").value("Personal notes"));
    }

    @Test
    void rejectsMalformedFilesButReportsInvalidJsonRows() throws Exception {
        byte[] wrongHeaders = "leetcodeId,title\n1,Test".getBytes(StandardCharsets.UTF_8);
        upload("preview", "CSV", wrongHeaders, "SKIP_EXISTING")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());
        byte[] jsonRows = """
                {"version":1,"problems":[{},{"leetcodeId":"2","topics":[7]},false]}
                """.getBytes(StandardCharsets.UTF_8);
        upload("preview", "JSON", jsonRows, "SKIP_EXISTING")
                .andExpect(status().isOk()).andExpect(jsonPath("$.invalid").value(3))
                .andExpect(jsonPath("$.rows[1].errors.topics").exists())
                .andExpect(jsonPath("$.rows[2].errors.row").exists());
    }

    @Test
    void backupApiRequiresConfirmationAndRejectsInvalidOrUnknownBackups() throws Exception {
        problem(1, topic("Arrays"));
        var result = mvc.perform(post("/api/backups")).andExpect(status().isCreated()).andReturn();
        String backupId = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/backups/{id}/restore", backupId).contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"yes\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/backups/{id}/restore", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"RESTORE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/backups/{id}/restore", backupId).contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"RESTORE\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.restartRequired").value(true));
        mvc.perform(get("/api/backups")).andExpect(jsonPath("$.pendingRestoreId").value(backupId));
        mvc.perform(get("/api/problems")).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(delete("/api/backups/restore")).andExpect(status().isNoContent());

        Path file = backupFile(temporaryDirectory.resolve("backups"), UUID.fromString(backupId));
        Files.writeString(file, "invalid sqlite");
        mvc.perform(post("/api/backups/{id}/restore", backupId).contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"RESTORE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.id").exists());
        mvc.perform(get("/api/backups")).andExpect(jsonPath("$.pendingRestoreId").doesNotExist());
    }

    @Test
    void appliesStagedRestoreOnlyAfterLockReleaseAndRetainsRecoveryCopy() throws Exception {
        Path database = temporaryDirectory.resolve("restart.sqlite");
        String url = "jdbc:sqlite:" + database;
        Path root = temporaryDirectory.resolve("restart-backups");
        Flyway.configure().dataSource(url, "", "").load().migrate();
        insertTopic(url, "Original");
        try (var store = DatabaseBackups.open(database.toString(), root.toString(), CLOCK)) {
            var original = store.create();
            insertTopic(url, "After backup");
            store.stage(original.id(), "RESTORE");
            assertThat(topicCount(url)).isEqualTo(2);
            assertThatThrownBy(() -> DatabaseBackups.open(database.toString(), root.toString(), CLOCK))
                    .isInstanceOf(IOException.class).hasMessageContaining("already using");
        }
        try (var restarted = DatabaseBackups.open(database.toString(), root.toString(), CLOCK)) {
            assertThat(topicCount(url)).isEqualTo(1);
            assertThat(restarted.list().pendingRestoreId()).isNull();
            assertThat(restarted.list().backups()).hasSize(2);
        }
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA integrity_check")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("ok");
        }
    }

    @Test
    void refusesAValidButDifferentDatabaseAfterRestoreConfirmation() throws Exception {
        Path database = temporaryDirectory.resolve("digest.sqlite");
        String url = "jdbc:sqlite:" + database;
        Path root = temporaryDirectory.resolve("digest-backups");
        Flyway.configure().dataSource(url, "", "").load().migrate();
        insertTopic(url, "Original");
        try (var store = DatabaseBackups.open(database.toString(), root.toString(), CLOCK)) {
            var confirmed = store.create();
            insertTopic(url, "Second snapshot");
            var different = store.create();
            insertTopic(url, "Current live data");
            store.stage(confirmed.id(), "RESTORE");
            Files.copy(backupFile(root, different.id()), backupFile(root, confirmed.id()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        assertThatThrownBy(() -> DatabaseBackups.open(database.toString(), root.toString(), CLOCK))
                .isInstanceOf(IOException.class).hasMessageContaining("confirmed backup changed");
        assertThat(topicCount(url)).isEqualTo(3);
    }

    private static Path backupFile(Path root, UUID backupId) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(backupId + ".sqlite"))
                    .findFirst().orElseThrow();
        }
    }

    @Test
    void appliesV4ToAnExistingV3Database() {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v3.sqlite");
        var previous = Flyway.configure().dataSource(url, "", "").target("3").load();
        assertThat(previous.migrate().migrationsExecuted).isEqualTo(3);
        var current = Flyway.configure().dataSource(url, "", "").target("4").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(current.migrate().migrationsExecuted).isZero();
    }

    @Test
    void preservesExistingGoalsWhenAddingDifficultyInV5() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("upgrade-v4.sqlite");
        Flyway.configure().dataSource(url, "", "").target("4").load().migrate();
        try (var database = DriverManager.getConnection(url); var sql = database.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO goals (id, name, type, start_date, end_date, target_count)
                    VALUES (7, 'Existing goal', 'DAILY', '2026-11-20', '2026-11-20', 4)
                    """);
        }
        var current = Flyway.configure().dataSource(url, "", "").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("5");
        current.validate();
        try (var database = DriverManager.getConnection(url); var sql = database.createStatement();
             var goal = sql.executeQuery("SELECT name, target_count, difficulty FROM goals WHERE id = 7")) {
            assertThat(goal.next()).isTrue();
            assertThat(goal.getString("name")).isEqualTo("Existing goal");
            assertThat(goal.getInt("target_count")).isEqualTo(4);
            assertThat(goal.getString("difficulty")).isNull();
        }
    }

    private static void insertTopic(String url, String name) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var insert = connection.prepareStatement("INSERT INTO topics(name, name_key, created_at) VALUES (?, ?, '2026-11-20T00:00:00.000Z')")) {
            insert.setString(1, name);
            insert.setString(2, name);
            insert.executeUpdate();
        }
    }

    private static int topicCount(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM topics")) {
            result.next();
            return result.getInt(1);
        }
    }

    private ResultActions upload(String action, String format, byte[] data, String mode) throws Exception {
        return mvc.perform(multipart("/api/exchange/" + action)
                .file(new MockMultipartFile("file", "catalog." + format.toLowerCase(), "application/octet-stream", data))
                .param("format", format).param("mode", mode));
    }

    private ResultActions revision(long problem, String date) throws Exception {
        return mvc.perform(post("/api/revisions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("problemId", problem, "date", date))));
    }

    private long topic(String name) throws Exception {
        return id(mvc.perform(post("/api/topics").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name)))));
    }

    private long problem(long number, Long... topics) throws Exception {
        return id(mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("leetcodeId", number, "title", "Problem " + number,
                        "difficulty", "EASY", "url", "https://leetcode.com/problems/sample-" + number + "/", "topicIds", List.of(topics))))));
    }

    private long id(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private void progress(long id, Map<String, String> patch) throws Exception {
        mvc.perform(patch("/api/problems/{id}/progress", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(patch))).andExpect(status().isOk());
    }
}
