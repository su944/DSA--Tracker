package dev.dsatracker.revision;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.revision.RevisionDtos.Counts;
import dev.dsatracker.revision.RevisionDtos.ScheduledReview;
import dev.dsatracker.revision.RevisionDtos.Filter;
import dev.dsatracker.revision.RevisionDtos.Status;
import dev.dsatracker.revision.RevisionDtos.Task;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RevisionRepository {
    private static final String TASKS = """
            SELECT r.*, p.leetcode_id, p.title
            FROM revision_tasks r JOIN problems p ON p.id = r.problem_id
            """;

    private final JdbcTemplate jdbc;

    RevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Task> find(long id) {
        return jdbc.query(TASKS + " WHERE r.id = ?", RevisionRepository::task, id).stream().findFirst();
    }

    List<Task> list(LocalDate today, Filter filter) {
        String clause = switch (filter) {
            case DUE_TODAY -> "r.status = 'SCHEDULED' AND r.scheduled_date = ?";
            case OVERDUE -> "r.status = 'SCHEDULED' AND r.scheduled_date < ?";
            case UPCOMING -> "r.status = 'SCHEDULED' AND r.scheduled_date > ?";
            case COMPLETED -> "r.status = 'COMPLETED'";
            case CANCELLED -> "r.status = 'CANCELLED'";
        };
        boolean closed = filter == Filter.COMPLETED || filter == Filter.CANCELLED;
        return jdbc.query(TASKS + " WHERE " + clause
                        + (closed ? " ORDER BY r.updated_at DESC, r.id DESC" : " ORDER BY r.scheduled_date, r.id"),
                RevisionRepository::task, closed ? new Object[]{} : new Object[]{today.toString()});
    }

    List<ScheduledReview> scheduledBetween(LocalDate start, LocalDate end, int limit) {
        return jdbc.query("""
                SELECT r.id, r.problem_id, p.leetcode_id, p.title, p.difficulty, r.scheduled_date,
                       (SELECT MAX(done.completed_at)
                        FROM revision_tasks done
                        WHERE done.problem_id = r.problem_id AND done.status = 'COMPLETED') AS latest_reviewed_at
                FROM revision_tasks r JOIN problems p ON p.id = r.problem_id
                WHERE r.status = 'SCHEDULED' AND r.scheduled_date BETWEEN ? AND ?
                ORDER BY r.scheduled_date, r.id
                LIMIT ?
                """, (row, index) -> {
            String latest = row.getString("latest_reviewed_at");
            return new ScheduledReview(row.getLong("id"), row.getLong("problem_id"), row.getLong("leetcode_id"),
                    row.getString("title"), Difficulty.valueOf(row.getString("difficulty")),
                    LocalDate.parse(row.getString("scheduled_date")), latest == null ? null : Instant.parse(latest));
        }, start.toString(), end.toString(), limit);
    }

    List<Task> query(LocalDate start, LocalDate end, LocalDate dueBefore, Status status, int offset, int limit) {
        String statusName = status == null ? null : status.name();
        String lower = start == null ? null : start.toString();
        String upper = end == null ? null : end.toString();
        String due = dueBefore == null ? null : dueBefore.toString();
        return jdbc.query(TASKS + """
                WHERE (? IS NULL OR r.scheduled_date >= ?) AND (? IS NULL OR r.scheduled_date <= ?)
                AND (? IS NULL OR (r.status = 'SCHEDULED' AND r.scheduled_date < ?))
                AND (? IS NULL OR r.status = ?)
                ORDER BY r.scheduled_date, r.id LIMIT ? OFFSET ?
                """, RevisionRepository::task, lower, lower, upper, upper, due, due, statusName, statusName, limit, offset);
    }

    Optional<Long> firstScheduledId(long problemId) {
        return jdbc.query("""
                SELECT id FROM revision_tasks
                WHERE problem_id = ? AND status = 'SCHEDULED'
                ORDER BY scheduled_date, id LIMIT 1
                """, (row, index) -> row.getLong("id"), problemId).stream().findFirst();
    }

    Counts counts(LocalDate today) {
        return jdbc.queryForObject("""
                SELECT count(CASE WHEN scheduled_date = ? THEN 1 END),
                       count(CASE WHEN scheduled_date < ? THEN 1 END),
                       count(CASE WHEN scheduled_date > ? THEN 1 END)
                FROM revision_tasks WHERE status = 'SCHEDULED'
                """, (row, index) -> new Counts(row.getLong(1), row.getLong(2), row.getLong(3)),
                today.toString(), today.toString(), today.toString());
    }

    Optional<Long> insert(long problemId, LocalDate date, String now, boolean initial) {
        return jdbc.query("""
                INSERT INTO revision_tasks(problem_id, scheduled_date, status, created_at, updated_at, initial_schedule)
                VALUES (?, ?, 'SCHEDULED', ?, ?, ?)
                ON CONFLICT(problem_id, scheduled_date) WHERE status = 'SCHEDULED' DO NOTHING
                RETURNING id
                """, (row, index) -> row.getLong(1), problemId, date.toString(), now, now, initial ? 1 : 0)
                .stream().findFirst();
    }

    boolean activeOn(long problem, LocalDate date, long excluding) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM revision_tasks
                WHERE problem_id = ? AND scheduled_date = ? AND status = 'SCHEDULED' AND id <> ?)
                """, Boolean.class, problem, date.toString(), excluding));
    }

    void move(long id, LocalDate date, String now) {
        jdbc.update("UPDATE revision_tasks SET scheduled_date = ?, initial_schedule = 0, updated_at = ? WHERE id = ?",
                date.toString(), now, id);
    }

    void close(long id, Status status, String now, String completedAt) {
        jdbc.update("UPDATE revision_tasks SET status = ?, completed_at = ?, updated_at = ? WHERE id = ?",
                status.name(), status == Status.COMPLETED ? completedAt : null, now, id);
    }

    void cancelForProblem(long problemId, String now) {
        jdbc.update("""
                UPDATE revision_tasks SET status = 'CANCELLED', updated_at = ?
                WHERE problem_id = ? AND status = 'SCHEDULED'
                """, now, problemId);
    }

    void correctInitialDate(long problemId, LocalDate date, String now) {
        jdbc.update("""
                UPDATE revision_tasks AS initial
                SET scheduled_date = ?, updated_at = ?,
                    status = CASE WHEN EXISTS (
                        SELECT 1 FROM revision_tasks other
                        WHERE other.problem_id = initial.problem_id AND other.id <> initial.id
                          AND other.scheduled_date = ? AND other.status = 'SCHEDULED'
                    ) THEN 'CANCELLED' ELSE 'SCHEDULED' END
                WHERE problem_id = ? AND status = 'SCHEDULED' AND initial_schedule = 1
                """, date.toString(), now, date.toString(), problemId);
    }

    private static Task task(ResultSet row, int index) throws SQLException {
        String completed = row.getString("completed_at");
        return new Task(row.getLong("id"), row.getLong("problem_id"), row.getLong("leetcode_id"), row.getString("title"),
                LocalDate.parse(row.getString("scheduled_date")), completed == null ? null : Instant.parse(completed),
                Status.valueOf(row.getString("status")), Instant.parse(row.getString("created_at")),
                Instant.parse(row.getString("updated_at")));
    }
}
