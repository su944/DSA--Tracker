package dev.dsatracker.reporting;

import dev.dsatracker.catalog.DateColumns;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.reporting.ReportingDtos.ActivityDay;
import dev.dsatracker.reporting.ReportingDtos.DifficultyStats;
import dev.dsatracker.reporting.ReportingDtos.RecentProblem;
import dev.dsatracker.reporting.ReportingDtos.Summary;
import dev.dsatracker.reporting.ReportingDtos.TopicStats;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReportingRepository {
    private static final DateColumns.Timestamp TIMESTAMPS = new DateColumns.Timestamp();

    private static final String DATED_SOLVE = "r.solved_date BETWEEN :firstDay AND :lastDay";

    private static final String RECENT_COLUMNS = """
            p.id, p.leetcode_id, p.title, p.difficulty, COALESCE(r.status, 'NOT_STARTED') AS status,
            r.solved_date, MAX(p.updated_at, COALESCE(r.updated_at, p.updated_at)) AS modified_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ReportingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Summary summary() {
        return jdbc.getJdbcOperations().queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(CASE WHEN r.status = 'SOLVED' THEN 1 END) AS solved,
                       COUNT(CASE WHEN r.status = 'ATTEMPTED' THEN 1 END) AS attempted
                FROM problems p
                LEFT JOIN problem_progress r ON r.problem_id = p.id
                """, (row, index) -> {
            long total = row.getLong("total");
            long solved = row.getLong("solved");
            long attempted = row.getLong("attempted");
            return new Summary(total, solved, attempted, total - solved - attempted);
        });
    }

    List<TopicStats> topics(ReportingPeriod period) {
        return topics(period, -1, 0);
    }

    List<TopicStats> topics(ReportingPeriod period, int limit, int offset) {
        // Each topic/problem pair is unique, even when a problem belongs to several topics.
        String sql = """
                SELECT t.id, t.name, COUNT(pt.problem_id) AS total,
                       COUNT(CASE WHEN r.status = 'SOLVED' AND (:allTime OR %s) THEN 1 END) AS solved,
                       COUNT(pt.problem_id) - COUNT(CASE WHEN r.status = 'SOLVED' THEN 1 END) AS remaining
                FROM topics t
                LEFT JOIN problem_topics pt ON pt.topic_id = t.id
                LEFT JOIN problem_progress r ON r.problem_id = pt.problem_id
                GROUP BY t.id, t.name, t.name_key
                ORDER BY t.name_key, t.id
                LIMIT :limit OFFSET :offset
                """.formatted(DATED_SOLVE);
        return jdbc.query(sql, dates(period).addValue("allTime", period.allTime() ? 1 : 0).addValue("limit", limit).addValue("offset", offset), (row, index) ->
                new TopicStats(row.getLong("id"), row.getString("name"), row.getLong("total"),
                        row.getLong("solved"), row.getLong("remaining")));
    }

    List<DifficultyStats> difficulty(ReportingPeriod period) {
        String filter = period.allTime() ? "" : " AND " + DATED_SOLVE;
        String sql = """
                SELECT p.difficulty, COUNT(*) AS solved
                FROM problem_progress r
                JOIN problems p ON p.id = r.problem_id
                WHERE r.status = 'SOLVED'%s
                GROUP BY p.difficulty
                ORDER BY CASE p.difficulty WHEN 'EASY' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END
                """.formatted(filter);
        return jdbc.query(sql, dates(period), (row, index) ->
                new DifficultyStats(Difficulty.valueOf(row.getString("difficulty")), row.getLong("solved")));
    }

    List<ActivityDay> activity(ReportingPeriod period) {
        return activity(period, -1);
    }

    List<ActivityDay> activity(ReportingPeriod period, int limit) {
        String sql = """
                SELECT r.solved_date, COUNT(*) AS solved
                FROM problem_progress r
                WHERE r.status = 'SOLVED' AND %s
                GROUP BY r.solved_date
                ORDER BY r.solved_date %s
                LIMIT :limit
                """.formatted(DATED_SOLVE, limit < 0 ? "ASC" : "DESC");
        return jdbc.query(sql, dates(period).addValue("limit", limit), (row, index) ->
                new ActivityDay(LocalDate.parse(row.getString("solved_date")), row.getLong("solved")));
    }

    long datedSolves(ReportingPeriod period) {
        return jdbc.queryForObject("SELECT count(*) FROM problem_progress r WHERE r.status = 'SOLVED' AND "
                + DATED_SOLVE, dates(period), Long.class);
    }

    List<RecentProblem> recentlySolved(ReportingPeriod period, int limit) {
        String sql = """
                SELECT %s
                FROM problem_progress r
                JOIN problems p ON p.id = r.problem_id
                WHERE r.status = 'SOLVED' AND %s
                ORDER BY r.solved_date DESC, modified_at DESC, p.id DESC
                LIMIT :limit
                """.formatted(RECENT_COLUMNS, DATED_SOLVE);
        return jdbc.query(sql, dates(period).addValue("limit", limit), (row, index) -> recentProblem(row, period.zone()));
    }

    List<RecentProblem> recentlyModified(ReportingPeriod period, int limit) {
        String filter = period.allTime() ? "" : "WHERE modified_at >= :firstInstant AND modified_at < :nextDay";
        String sql = """
                WITH problem_changes AS (
                    SELECT %s
                    FROM problems p
                    LEFT JOIN problem_progress r ON r.problem_id = p.id
                )
                SELECT * FROM problem_changes
                %s
                ORDER BY modified_at DESC, id DESC
                LIMIT :limit
                """.formatted(RECENT_COLUMNS, filter);
        var parameters = new MapSqlParameterSource("limit", limit);
        if (!period.allTime()) {
            parameters.addValue("firstInstant", TIMESTAMPS.convertToDatabaseColumn(
                    period.start().atStartOfDay(period.zone()).toInstant()));
            parameters.addValue("nextDay", TIMESTAMPS.convertToDatabaseColumn(
                    period.today().plusDays(1).atStartOfDay(period.zone()).toInstant()));
        }
        return jdbc.query(sql, parameters, (row, index) -> recentProblem(row, period.zone()));
    }

    private static MapSqlParameterSource dates(ReportingPeriod period) {
        return new MapSqlParameterSource("firstDay", period.firstDatedSolve().toString())
                .addValue("lastDay", period.today().toString());
    }

    private static RecentProblem recentProblem(ResultSet row, ZoneId zone) throws SQLException {
        String solvedDate = row.getString("solved_date");
        var modified = TIMESTAMPS.convertToEntityAttribute(row.getString("modified_at"));
        return new RecentProblem(row.getLong("id"), row.getLong("leetcode_id"), row.getString("title"),
                Difficulty.valueOf(row.getString("difficulty")), ProgressStatus.valueOf(row.getString("status")),
                solvedDate == null ? null : LocalDate.parse(solvedDate),
                modified, modified.atZone(zone).toLocalDate());
    }
}
