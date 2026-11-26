package dev.dsatracker.planning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import dev.dsatracker.catalog.CatalogDtos.TopicView;
import dev.dsatracker.catalog.Difficulty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public final class GoalDtos {
    private GoalDtos() {}

    public enum GoalType { DAILY, WEEKLY, CUSTOM }

    public static final class GoalInput {
        @NotBlank @Size(max = 100) private final String name;
        @NotNull private final GoalType type;
        @NotNull private final LocalDate startDate;
        private final LocalDate endDate;
        @NotNull @Positive private final Integer targetCount;
        @Positive private final Long topicId;
        private Difficulty difficulty;
        private boolean difficultyProvided;

        @JsonCreator
        public GoalInput(String name, GoalType type, LocalDate startDate, LocalDate endDate,
                         Integer targetCount, Long topicId) {
            this.name = name;
            this.type = type;
            this.startDate = startDate;
            this.endDate = endDate;
            this.targetCount = targetCount;
            this.topicId = topicId;
        }

        @JsonSetter
        public void setDifficulty(Difficulty difficulty) {
            this.difficulty = difficulty;
            this.difficultyProvided = true;
        }

        public String name() { return name; }
        public GoalType type() { return type; }
        public LocalDate startDate() { return startDate; }
        public LocalDate endDate() { return endDate; }
        public Integer targetCount() { return targetCount; }
        public Long topicId() { return topicId; }
        public Difficulty difficulty() { return difficulty; }
        public boolean difficultyProvided() { return difficultyProvided; }
    }

    public record GoalPatch(String name, Integer targetCount, LocalDate startDate, LocalDate endDate,
                            boolean topicProvided, Long topicId, boolean difficultyProvided, Difficulty difficulty) {}

    public record GoalProgress(long solvedCount, int targetCount, long remainingCount, boolean complete) {
        static GoalProgress from(long solved, int target) {
            return new GoalProgress(solved, target, Math.max(0L, target - solved), solved >= target);
        }
    }

    public record GoalView(long id, String name, GoalType type, LocalDate startDate, LocalDate endDate,
                           TopicView topic, GoalProgress progress, Difficulty difficulty) {
        static GoalView from(GoalRepository.Tally tally) {
            Goal goal = tally.getGoal();
            return new GoalView(goal.id, goal.name, goal.type, goal.startDate, goal.endDate,
                    goal.topic == null ? null : TopicView.from(goal.topic),
                    GoalProgress.from(tally.getSolvedCount(), goal.targetCount), goal.difficulty);
        }
    }
}
