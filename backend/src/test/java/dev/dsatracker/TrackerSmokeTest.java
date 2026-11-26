package dev.dsatracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TrackerSmokeTest {
    @TempDir
    static Path temporaryDirectory;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry properties) {
        properties.add("tracker.database-path", () -> temporaryDirectory.resolve("test.sqlite").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void returnsHealthFromRunningApplication() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void mcpIsDisabledByDefault() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsLocalDevelopmentOrigin() throws Exception {
        mvc.perform(options("/api/health")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void rejectsOtherOrigins() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "https://example.com"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void createsFileBackedDatabaseAndAppliesBaseline() {
        assertThat(temporaryDirectory.resolve("test.sqlite")).isRegularFile();
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version = '1' AND success = 1
                """, Integer.class)).isEqualTo(1);
    }
    @Test
    void onlyAcceptsLoopbackHostHeaders() throws Exception {
        mvc.perform(get("/api/health").header("Host", "localhost:5173")).andExpect(status().isOk());
        mvc.perform(get("/api/health").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/health").header("Host", "tracker.invalid")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsFractionalIntegerFieldsAndNumericStatuses() throws Exception {
        mvc.perform(post("/api/goals").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Target","type":"DAILY","startDate":"2026-11-20","targetCount":1.5}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.targetCount").exists());
        mvc.perform(post("/api/problems").contentType(MediaType.APPLICATION_JSON).content("""
                {"leetcodeId":1.5,"title":"Two Sum","url":"https://leetcode.com/problems/two-sum/","difficulty":"EASY","topicIds":[1]}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.leetcodeId").exists());
        mvc.perform(patch("/api/problems/1/progress").contentType(MediaType.APPLICATION_JSON).content("""
                {"status":1}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.status").exists());
    }

}
