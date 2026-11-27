package dev.dsatracker.revision;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.DateColumns;
import dev.dsatracker.catalog.ProblemRepository;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.revision.RevisionDtos.Counts;
import dev.dsatracker.revision.RevisionDtos.Filter;
import dev.dsatracker.revision.RevisionDtos.Queue;
import dev.dsatracker.revision.RevisionDtos.ScheduledReview;
import dev.dsatracker.revision.RevisionDtos.Status;
import dev.dsatracker.revision.RevisionDtos.Task;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevisionService {
    public static final int INTERVAL_DAYS = 7;
    private final RevisionRepository revisions;
    private final ProblemRepository problems;
    private final Clock clock;
    private final DateColumns.Timestamp timestamps = new DateColumns.Timestamp();

    RevisionService(RevisionRepository revisions, ProblemRepository problems, Clock clock) {
        this.revisions = revisions;
        this.problems = problems;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Queue queue(Filter filter) {
        var today = LocalDate.now(clock);
        return new Queue(today, counts(today), revisions.list(today, filter));
    }

    @Transactional(readOnly = true)
    public List<ScheduledReview> scheduledBetween(LocalDate start, LocalDate end, int limit) {
        checkedDate(start);
        checkedDate(end);
        if (limit < 1 || limit > 100) {
            throw ApiException.invalid("limit", "Revision limit must be from 1 to 100.");
        }
        return revisions.scheduledBetween(start, end, limit);
    }

    @Transactional(readOnly = true)
    public List<Task> query(dev.dsatracker.query.TrackerQueryRequest.Revisions filter, int offset, int limit) {
        return revisions.query(filter.startDate(), filter.endDate(), filter.dueBefore(), filter.status(), offset, limit);
    }

    public Counts counts(LocalDate date) {
        return revisions.counts(date);
    }

    Task get(long id) {
        if (id <= 0) throw ApiException.invalid("id", "Revision ID must be positive.");
        return revisions.find(id).orElseThrow(() -> ApiException.missing("Revision not found."));
    }

    @Transactional
    Task schedule(long problemId, LocalDate date) {
        checkedDate(date);
        requireSolved(problemId);
        long id = revisions.insert(problemId, date, now(), false).orElseThrow(RevisionService::duplicate);
        return get(id);
    }

    @Transactional
    Task move(long id, LocalDate date) {
        checkedDate(date);
        var task = requireScheduled(id);
        requireSolved(task.problemId());
        if (revisions.activeOn(task.problemId(), date, id)) throw duplicate();
        revisions.move(id, date, now());
        return get(id);
    }

    @Transactional
    Task finish(long id, Status status) {
        return finish(id, status, LocalDate.now(clock));
    }

    public record CompletedReview(Task revision, LocalDate nextRevisionDate) {}

    @Transactional
    public CompletedReview completeByLeetcodeId(long leetcodeId, LocalDate reviewedDate) {
        if (leetcodeId < 1) throw ApiException.invalid("leetcodeId", "LeetCode ID must be positive.");
        var date = reviewedDate == null ? LocalDate.now(clock) : reviewedDate;
        checkedDate(date);
        if (date.isAfter(LocalDate.now(clock))) {
            throw ApiException.invalid("reviewedDate", "Review date cannot be later than today.");
        }
        long problemId = problems.findIdByLeetcodeId(leetcodeId)
                .orElseThrow(() -> ApiException.missing("No problem found with LeetCode ID " + leetcodeId + "."));
        long revisionId = revisions.firstScheduledId(problemId)
                .orElseThrow(() -> ApiException.missing("No active revision found for LeetCode ID " + leetcodeId + "."));
        checkedDate(date.plusDays(INTERVAL_DAYS));
        return new CompletedReview(finish(revisionId, Status.COMPLETED, date), date.plusDays(INTERVAL_DAYS));
    }

    private Task finish(long id, Status status, LocalDate reviewedDate) {
        var task = get(id);
        if (task.status() == status) return task;
        if (task.status() != Status.SCHEDULED) {
            throw ApiException.conflict("status", "Only scheduled revisions can be changed.");
        }
        String timestamp = now();
        if (status == Status.COMPLETED) {
            requireSolved(task.problemId());
        }
        String completedAt = reviewedDate.equals(LocalDate.now(clock)) ? timestamp
                : timestamps.convertToDatabaseColumn(reviewedDate.atStartOfDay(clock.getZone()).toInstant());
        revisions.close(id, status, timestamp, completedAt);
        if (status == Status.COMPLETED) {
            revisions.insert(task.problemId(), reviewedDate.plusDays(INTERVAL_DAYS), timestamp, false);
        }
        return get(id);
    }

    @Transactional
    public void reconcile(long problemId, ProgressStatus before, LocalDate oldSolved,
                          ProgressStatus after, LocalDate solved) {
        if (after != ProgressStatus.SOLVED) {
            if (before == ProgressStatus.SOLVED) revisions.cancelForProblem(problemId, now());
        } else if (before != ProgressStatus.SOLVED) {
            revisions.insert(problemId, solved.plusDays(INTERVAL_DAYS), now(), true);
        } else if (!Objects.equals(oldSolved, solved)) {
            revisions.correctInitialDate(problemId, solved.plusDays(INTERVAL_DAYS), now());
        }
    }

    private Task requireScheduled(long id) {
        var task = get(id);
        if (task.status() != Status.SCHEDULED) {
            throw ApiException.conflict("status", "Only scheduled revisions can be changed.");
        }
        return task;
    }

    private void requireSolved(long id) {
        var reference = problems.findById(id).orElseThrow(() -> ApiException.missing("Problem not found.")).reference();
        if (reference.status() != ProgressStatus.SOLVED) {
            throw ApiException.conflict("problemId", "Only solved problems can be scheduled for revision.");
        }
    }

    private static void checkedDate(LocalDate date) {
        if (date.getYear() < 1 || date.getYear() > 9999) {
            throw ApiException.invalid("date", "Enter a date with a year from 0001 to 9999.");
        }
    }

    private static ApiException duplicate() {
        return ApiException.conflict("date", "This problem already has a scheduled revision on that date.");
    }

    private String now() {
        return timestamps.convertToDatabaseColumn(clock.instant());
    }
}
