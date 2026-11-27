package dev.dsatracker.query;

import static dev.dsatracker.query.TrackerQueryRequest.*;

import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.planning.GoalService;
import dev.dsatracker.planning.PlannerService;
import dev.dsatracker.reporting.ReportingService;
import dev.dsatracker.revision.RevisionDtos.Status;
import dev.dsatracker.revision.RevisionService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class TrackerQueryService {
    private final TrackerQueryValidator validator;
    private final ProblemService problems;
    private final GoalService goals;
    private final PlannerService planner;
    private final RevisionService revisions;
    private final ReportingService reports;
    private final Clock clock;

    TrackerQueryService(TrackerQueryValidator validator, ProblemService problems, GoalService goals, PlannerService planner,
                        RevisionService revisions, ReportingService reports, Clock clock) {
        this.validator = validator;
        this.problems = problems;
        this.goals = goals;
        this.planner = planner;
        this.revisions = revisions;
        this.reports = reports;
        this.clock = clock;
    }

    public Result execute(Domain domain, JsonNode json) {
        Filters filters = validator.parse(domain, json);
        int page = filters.page() == null ? 0 : filters.page();
        int limit = filters.limit() == null ? 20 : filters.limit();
        int offset = page * limit;
        return switch (filters) {
            case Problems p -> page(domain, page, problems.query(p, page, limit));
            case Goals g -> page(domain, page, goals.query(g, page, limit).map(goal ->
                    new GoalItem(goal.id(), goal.name(), goal.type(), goal.startDate(), goal.endDate(),
                            goal.difficulty(), goal.topic() == null ? null : goal.topic().name(), goal.progress())));
            case Planner p -> page(domain, page, planner.query(p, page, limit).map(task ->
                    new PlannerItem(task.id(), task.problem().leetcodeId(), task.problem().title(), task.difficulty(),
                            task.date(), task.state())));
            case Revisions r -> bounded(domain, page, limit, revisions.query(r, offset, limit + 1).stream().map(task ->
                    new RevisionItem(task.id(), task.leetcodeId(), task.title(), task.scheduledDate(), task.status(),
                            timing(task.status(), task.scheduledDate(), LocalDate.now(clock)))).toList());
            case Analytics a -> analytics(a, page, limit, offset);
        };
    }

    private Result analytics(Analytics filters, int page, int limit, int offset) {
        String range = filters.range() == null ? (filters.report() == Report.SOLVING_ACTIVITY ? "30" : "all") : filters.range();
        return switch (filters.report()) {
            case DASHBOARD_SUMMARY -> result(Domain.ANALYTICS, page, false, List.of(reports.summary()));
            case TOPIC_PROGRESS -> bounded(Domain.ANALYTICS, page, limit, reports.topics(range, limit + 1, offset));
            case DIFFICULTY_PROGRESS -> result(Domain.ANALYTICS, page, false, reports.difficulty(range));
            case SOLVING_ACTIVITY -> {
                var data = reports.activityWindow(range);
                yield new Result(Domain.ANALYTICS, page, data.activity().days().size(), false, data.activity().days(), data.truncated());
            }
            case RECENT_PROBLEMS -> {
                var recent = reports.recent(range, filters.limit() == null ? 10 : limit);
                yield result(Domain.ANALYTICS, page, false, List.of(new RecentItems(
                        recent.solved().stream().map(TrackerQueryService::recent).toList(),
                        recent.modified().stream().map(TrackerQueryService::recent).toList())));
            }
        };
    }

    private static RecentItem recent(dev.dsatracker.reporting.ReportingDtos.RecentProblem value) {
        return new RecentItem(value.leetcodeId(), value.title(), value.status(), value.solvedDate());
    }

    private static Timing timing(Status status, LocalDate date, LocalDate today) {
        if (status != Status.SCHEDULED) return Timing.INACTIVE;
        if (date.isBefore(today)) return Timing.OVERDUE;
        return date.equals(today) ? Timing.DUE_TODAY : Timing.UPCOMING;
    }

    private static Result page(Domain domain, int page, Slice<?> rows) {
        return result(domain, page, rows.hasNext(), rows.getContent());
    }

    private static Result bounded(Domain domain, int page, int limit, List<?> rows) {
        return result(domain, page, rows.size() > limit, rows.stream().limit(limit).toList());
    }

    private static Result result(Domain domain, int page, boolean more, List<?> rows) {
        return new Result(domain, page, rows.size(), more, rows, false);
    }

    public record Result(Domain domain, int page, int returnedCount, boolean hasMore, List<?> items, boolean truncated) {}
    public record GoalItem(long goalId, String name, dev.dsatracker.planning.GoalDtos.GoalType type,
                           LocalDate startDate, LocalDate endDate, dev.dsatracker.catalog.Difficulty difficulty,
                           String topicName, dev.dsatracker.planning.GoalDtos.GoalProgress progress) {}
    public record PlannerItem(long plannerEntryId, long leetcodeId, String title, dev.dsatracker.catalog.Difficulty difficulty,
                              LocalDate date, dev.dsatracker.planning.PlannerDtos.TaskState state) {}
    public enum Timing { OVERDUE, DUE_TODAY, UPCOMING, INACTIVE }
    public record RevisionItem(long revisionId, long leetcodeId, String title, LocalDate scheduledDate, Status status, Timing timing) {}
    public record RecentItem(long leetcodeId, String title, dev.dsatracker.catalog.ProgressStatus status, LocalDate solvedDate) {}
    public record RecentItems(List<RecentItem> solved, List<RecentItem> modified) {}
}
