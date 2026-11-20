package dev.dsatracker.reporting;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.planning.GoalDtos.GoalView;
import dev.dsatracker.planning.PlannerDtos.Day;
import dev.dsatracker.revision.RevisionDtos.Counts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReportingDtos {
    private ReportingDtos() {}

    public record Summary(long total, long solved, long attempted, long notStarted) {}

    public record Today(LocalDate date, Day plan, List<GoalView> goals, Counts revisions) {}

    public record TopicStats(long topicId, String name, long total, long solved, long remaining) {}

    public record DifficultyStats(Difficulty difficulty, long solved) {}

    public record ActivityDay(LocalDate date, long solved) {}

    public record Activity(LocalDate startDate, LocalDate endDate, List<ActivityDay> days) {}

    public record RecentProblem(long id, long leetcodeId, String title, Difficulty difficulty,
                                ProgressStatus status, LocalDate solvedDate, Instant modifiedAt, LocalDate modifiedDate) {}

    public record Recent(List<RecentProblem> solved, List<RecentProblem> modified) {}
}
