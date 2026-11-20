package dev.dsatracker.planning;

import dev.dsatracker.catalog.CatalogDtos.TopicView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public final class GoalDtos {
    private GoalDtos() {}

    public enum GoalType { DAILY, WEEKLY, CUSTOM }

    public record GoalInput(@NotBlank @Size(max = 100) String name, @NotNull GoalType type,
                            @NotNull LocalDate startDate, LocalDate endDate,
                            @NotNull @Positive Integer targetCount, @Positive Long topicId) {}

    public record GoalProgress(long solvedCount, int targetCount, long remainingCount, boolean complete) {
        static GoalProgress from(long solved, int target) {
            return new GoalProgress(solved, target, Math.max(0L, target - solved), solved >= target);
        }
    }

    public record GoalView(long id, String name, GoalType type, LocalDate startDate, LocalDate endDate,
                           TopicView topic, GoalProgress progress) {
        static GoalView from(GoalRepository.Tally tally) {
            Goal goal = tally.getGoal();
            return new GoalView(goal.id, goal.name, goal.type, goal.startDate, goal.endDate,
                    goal.topic == null ? null : TopicView.from(goal.topic),
                    GoalProgress.from(tally.getSolvedCount(), goal.targetCount));
        }
    }
}
