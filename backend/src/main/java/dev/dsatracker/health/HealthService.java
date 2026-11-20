package dev.dsatracker.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class HealthService {
    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcTemplate jdbc;

    HealthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean isAvailable() {
        try {
            return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (DataAccessException error) {
            log.warn("SQLite health check failed", error);
            return false;
        }
    }
}
