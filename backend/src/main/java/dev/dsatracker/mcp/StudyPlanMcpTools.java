package dev.dsatracker.mcp;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.planning.GoalDtos.GoalType;
import dev.dsatracker.planning.GoalDtos.GoalView;
import dev.dsatracker.planning.GoalService;
import dev.dsatracker.planning.PlannerDtos.Day;
import dev.dsatracker.planning.PlannerDtos.Task;
import dev.dsatracker.planning.PlannerDtos.TaskState;
import dev.dsatracker.planning.PlannerDtos.View;
import dev.dsatracker.planning.PlannerService;
import dev.dsatracker.revision.RevisionDtos.ScheduledReview;
import dev.dsatracker.revision.RevisionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class StudyPlanMcpTools {
    private final PlannerService planner;
    private final GoalService goals;
    private final RevisionService revisions;
    private final Clock clock;

    public StudyPlanMcpTools(PlannerService planner, GoalService goals, RevisionService revisions, Clock clock) {
        this.planner = planner;
        this.goals = goals;
        this.revisions = revisions;
        this.clock = clock;
    }

    @McpTool(name = "get_daily_plan", description = "Get scheduled, completed, and skipped problems for a day.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public DailyPlan getDailyPlan(
            @McpToolParam(required = false, description = "ISO date YYYY-MM-DD. Defaults to tracker today.") String date) {
        return DailyPlan.from(planner.calendar(dateOrToday("date", date), View.DAY).days().getFirst(), 100);
    }

    @McpTool(name = "get_weekly_plan", description = "Get the Monday–Sunday study plan grouped by date.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public WeeklyPlan getWeeklyPlan(
            @McpToolParam(required = false, description = "ISO date YYYY-MM-DD within the week. Defaults to tracker today.")
            String date) {
        var week = planner.calendar(dateOrToday("date", date), View.WEEK);
        var counts = week.counts();
        var days = new java.util.ArrayList<DailyPlan>();
        int remaining = 100;
        for (var day : week.days()) {
            var bounded = DailyPlan.from(day, remaining);
            remaining -= bounded.scheduledProblems().size();
            days.add(bounded);
        }
        return new WeeklyPlan(week.startDate(), week.endDate(), counts.planned(), counts.completed(), counts.skipped(),
                List.copyOf(days), counts.planned() > 100);
    }

    @McpTool(name = "list_goals", description = "List study goals with current progress and optional active filtering.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GoalList listGoals(
            @McpToolParam(required = false, description = "Only include goals whose period includes tracker today. Default: false.")
            Boolean activeOnly) {
        var selected = goals.listBounded(Boolean.TRUE.equals(activeOnly) ? LocalDate.now(clock) : null, 51);
        return new GoalList(selected.stream().limit(50).map(GoalSummary::from).toList(), selected.size() > 50);
    }

    @McpTool(name = "get_goal_progress", description = "Get the current progress and period of one study goal.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GoalDetails getGoalProgress(@McpToolParam(description = "Positive internal goal ID.") Long goalId) {
        if (goalId == null || goalId < 1) {
            throw ApiException.invalid("goalId", "Goal ID must be positive.");
        }
        return GoalDetails.from(goals.get(goalId));
    }

    @McpTool(name = "get_due_revisions", description = "List scheduled revisions due on or before a date.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public DueRevisions getDueRevisions(
            @McpToolParam(required = false, description = "Inclusive ISO cutoff date. Defaults to tracker today.") String date,
            @McpToolParam(required = false, description = "Results from 1 to 100. Default: 20.") Integer limit) {
        var cutoff = dateOrToday("date", date);
        var items = revisions.scheduledBetween(LocalDate.of(1, 1, 1), cutoff, limit == null ? 20 : limit)
                .stream().map(item -> new DueRevision(item.revisionId(), item.problemId(), item.leetcodeId(),
                        item.title(), item.difficulty(), item.scheduledDate(), item.scheduledDate().isBefore(cutoff)))
                .toList();
        return new DueRevisions(cutoff, items);
    }

    @McpTool(name = "get_upcoming_revisions", description = "List scheduled revisions in an inclusive calendar window.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public UpcomingRevisions getUpcomingRevisions(
            @McpToolParam(required = false, description = "First ISO date YYYY-MM-DD. Defaults to tracker today.") String fromDate,
            @McpToolParam(required = false, description = "Calendar days from 1 to 90, including the first date. Default: 7.") Integer days,
            @McpToolParam(required = false, description = "Results from 1 to 100. Default: 20.") Integer limit) {
        var start = dateOrToday("fromDate", fromDate);
        int window = days == null ? 7 : days;
        if (window < 1 || window > 90) {
            throw ApiException.invalid("days", "Revision window must be from 1 to 90 days.");
        }
        var end = start.plusDays(window - 1);
        var items = revisions.scheduledBetween(start, end, limit == null ? 20 : limit)
                .stream().map(this::upcoming).toList();
        return new UpcomingRevisions(start, end, items);
    }

    private UpcomingRevision upcoming(ScheduledReview item) {
        var latest = item.latestReviewedAt();
        return new UpcomingRevision(item.revisionId(), item.problemId(), item.leetcodeId(), item.title(),
                item.difficulty(), item.scheduledDate(), latest == null ? null : latest.atZone(clock.getZone()).toLocalDate());
    }

    private LocalDate dateOrToday(String field, String input) {
        if (input == null) {
            return LocalDate.now(clock);
        }
        if (input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try {
                var date = LocalDate.parse(input);
                if (date.getYear() > 0) {
                    return date;
                }
            } catch (DateTimeParseException ignored) {
                // Return a consistent input error for invalid calendar dates.
            }
        }
        throw ApiException.invalid(field, field + " must be a valid date in YYYY-MM-DD format (year 0001 to 9999).");
    }

    private static Long topicId(GoalView goal) {
        return goal.topic() == null ? null : goal.topic().id();
    }

    private static double percentage(GoalView goal) {
        var progress = goal.progress();
        return progress.complete() ? 100.0 : 100.0 * progress.solvedCount() / progress.targetCount();
    }

    public record DailyPlan(LocalDate date, int totalScheduled, long completedCount, long skippedCount,
                            List<ScheduledProblem> scheduledProblems, boolean truncated) {
        static DailyPlan from(Day day, int limit) {
            var counts = day.counts();
            return new DailyPlan(day.date(), counts.planned(), counts.completed(), counts.skipped(),
                    day.items().stream().limit(limit).map(ScheduledProblem::from).toList(), day.items().size() > limit);
        }
    }

    public record ScheduledProblem(long plannerEntryId, long problemId, long leetcodeId, String title,
                                   Difficulty difficulty, ProgressStatus progressStatus, LocalDate scheduledDate,
                                   boolean completed, boolean skipped) {
        static ScheduledProblem from(Task task) {
            var problem = task.problem();
            return new ScheduledProblem(task.id(), problem.id(), problem.leetcodeId(), problem.title(),
                    task.difficulty(), problem.status(), task.date(),
                    task.state() == TaskState.COMPLETED, task.state() == TaskState.SKIPPED);
        }
    }

    public record WeeklyPlan(LocalDate startDate, LocalDate endDate, int totalScheduled, long completedCount,
                             long skippedCount, List<DailyPlan> days, boolean truncated) {}

    public record GoalList(List<GoalSummary> goals, boolean truncated) {}

    public record GoalSummary(long goalId, String name, GoalType type, LocalDate startDate, LocalDate endDate,
                              int targetCount, long currentCount, double completionPercentage,
                              @Schema(nullable = true) Long topicId, @Schema(nullable = true) Difficulty difficulty) {
        static GoalSummary from(GoalView goal) {
            return new GoalSummary(goal.id(), goal.name(), goal.type(), goal.startDate(), goal.endDate(),
                    goal.progress().targetCount(), goal.progress().solvedCount(), percentage(goal),
                    StudyPlanMcpTools.topicId(goal), goal.difficulty());
        }
    }

    public record GoalDetails(long goalId, String name, GoalType type, LocalDate startDate, LocalDate endDate,
                              int targetCount, long solvedCount, long remainingCount, double completionPercentage,
                              boolean complete, @Schema(nullable = true) Long topicId,
                              @Schema(nullable = true) String topicName, @Schema(nullable = true) Difficulty difficulty) {
        static GoalDetails from(GoalView goal) {
            var progress = goal.progress();
            return new GoalDetails(goal.id(), goal.name(), goal.type(), goal.startDate(), goal.endDate(),
                    progress.targetCount(), progress.solvedCount(), progress.remainingCount(), percentage(goal),
                    progress.complete(), StudyPlanMcpTools.topicId(goal),
                    goal.topic() == null ? null : goal.topic().name(), goal.difficulty());
        }
    }

    public record DueRevisions(LocalDate date, List<DueRevision> revisions) {}

    public record DueRevision(long revisionId, long problemId, long leetcodeId, String title, Difficulty difficulty,
                              LocalDate nextRevisionDate, boolean overdue) {}

    public record UpcomingRevisions(LocalDate fromDate, LocalDate endDate, List<UpcomingRevision> revisions) {}

    public record UpcomingRevision(long revisionId, long problemId, long leetcodeId, String title, Difficulty difficulty,
                                   LocalDate nextRevisionDate, @Schema(nullable = true) LocalDate latestReviewedDate) {}
}
