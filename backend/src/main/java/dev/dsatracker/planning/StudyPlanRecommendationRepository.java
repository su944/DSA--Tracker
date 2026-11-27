package dev.dsatracker.planning;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class StudyPlanRecommendationRepository {
    private static final String FROM = """
            FROM problems p JOIN problem_progress r ON r.problem_id = p.id
            WHERE 1 = 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    StudyPlanRecommendationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long count(Filter filter) {
        return jdbc.queryForObject("SELECT count(*) " + where(filter), parameters(filter), Long.class);
    }

    List<Candidate> candidates(Filter filter, int limit) {
        return jdbc.query("""
                SELECT p.id, p.leetcode_id, p.title, p.difficulty, r.status
                """ + where(filter) + """
                ORDER BY CASE r.status WHEN 'NOT_STARTED' THEN 0 WHEN 'ATTEMPTED' THEN 1 ELSE 2 END,
                         p.leetcode_id
                LIMIT :limit
                """, parameters(filter).addValue("limit", limit), (row, index) ->
                new Candidate(row.getLong("id"), row.getLong("leetcode_id"), row.getString("title"),
                        Difficulty.valueOf(row.getString("difficulty")), ProgressStatus.valueOf(row.getString("status"))));
    }

    List<ExistingDate> existingDates(List<Candidate> candidates, Filter filter) {
        if (candidates.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT problem_id, planned_date FROM plan_items
                WHERE problem_id IN (:ids) AND planned_date BETWEEN :start AND :end
                """, parameters(filter).addValue("ids", candidates.stream().map(Candidate::problemId).toList()),
                (row, index) -> new ExistingDate(row.getLong("problem_id"), LocalDate.parse(row.getString("planned_date"))));
    }

    private static String where(Filter filter) {
        var sql = new StringBuilder(FROM);
        if (!filter.topicIds().isEmpty()) {
            sql.append("""
                    AND EXISTS (SELECT 1 FROM problem_topics t WHERE t.problem_id = p.id AND t.topic_id IN (:topicIds))
                    """);
        }
        if (filter.difficulty() != null) sql.append("AND p.difficulty = :difficulty\n");
        if (filter.excludeSolved()) sql.append("AND r.status <> 'SOLVED'\n");
        if (filter.excludeScheduled()) {
            sql.append("AND NOT EXISTS (SELECT 1 FROM plan_items s WHERE s.problem_id = p.id)\n");
        } else {
            sql.append("""
                    AND (SELECT count(*) FROM plan_items s WHERE s.problem_id = p.id
                         AND s.planned_date BETWEEN :start AND :end) < :days
                    """);
        }
        return sql.toString();
    }

    private static MapSqlParameterSource parameters(Filter filter) {
        return new MapSqlParameterSource("topicIds", filter.topicIds())
                .addValue("difficulty", filter.difficulty() == null ? null : filter.difficulty().name())
                .addValue("start", filter.start().toString()).addValue("end", filter.end().toString())
                .addValue("days", filter.days());
    }

    record Filter(List<Long> topicIds, Difficulty difficulty, boolean excludeSolved, boolean excludeScheduled,
                  LocalDate start, LocalDate end, int days) {}

    record Candidate(long problemId, long leetcodeId, String title, Difficulty difficulty, ProgressStatus status) {}

    record ExistingDate(long problemId, LocalDate date) {}
}
