package dev.dsatracker.query;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.planning.GoalDtos.GoalType;
import dev.dsatracker.planning.PlannerDtos.TaskState;
import dev.dsatracker.revision.RevisionDtos.Status;
import java.time.LocalDate;
import java.util.List;

public final class TrackerQueryRequest {
    private TrackerQueryRequest() {}

    public enum Domain { PROBLEMS, GOALS, PLANNER, REVISIONS, ANALYTICS }
    public enum SortKey {
        LEETCODE_ID("leetcodeId"), TITLE("title"), CREATED_AT("createdAt"), UPDATED_AT("updatedAt");
        private final String property;
        SortKey(String property) { this.property = property; }
        public String property() { return property; }
    }
    public enum Direction { ASC, DESC }
    public enum Report { DASHBOARD_SUMMARY, TOPIC_PROGRESS, DIFFICULTY_PROGRESS, SOLVING_ACTIVITY, RECENT_PROBLEMS }

    public sealed interface Filters permits Problems, Goals, Planner, Revisions, Analytics {
        Integer page();
        Integer limit();
    }

    public record Problems(String title, Long leetcodeId, List<String> topicNames, Difficulty difficulty,
                           List<ProgressStatus> statuses, Boolean excludeScheduled, LocalDate scheduledFrom,
                           LocalDate scheduledTo, LocalDate attemptedFrom, LocalDate attemptedTo, LocalDate solvedFrom,
                           LocalDate solvedTo, SortKey sortBy, Direction sortDirection, Integer page, Integer limit)
            implements Filters {}
    public record Goals(LocalDate activeOn, GoalType type, String topicName, Difficulty difficulty, Boolean completed,
                        LocalDate startDateFrom, LocalDate startDateTo, Integer limit, Integer page) implements Filters {}
    public record Planner(LocalDate startDate, LocalDate endDate, TaskState state, Difficulty difficulty,
                          Integer limit, Integer page) implements Filters {}
    public record Revisions(LocalDate dueBefore, LocalDate startDate, LocalDate endDate, Status status,
                            Integer limit, Integer page) implements Filters {}
    public record Analytics(Report report, String range, Integer limit, Integer page) implements Filters {}
}
