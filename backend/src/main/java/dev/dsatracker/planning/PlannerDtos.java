package dev.dsatracker.planning;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.dsatracker.catalog.CatalogDtos.ProblemReference;
import dev.dsatracker.catalog.Difficulty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;

public final class PlannerDtos {
    private PlannerDtos() {}

    public enum TaskState { PLANNED, COMPLETED, SKIPPED }
    public enum View { DAY, WEEK }

    public record PlanInput(@NotNull @Positive Long problemId, @NotNull LocalDate date) {}
    public record DateInput(@NotNull LocalDate date) {}
    public record SkipInput(@NotNull Boolean skipped) {}

    public record Task(long id, LocalDate date, ProblemReference problem, TaskState state,
                       @JsonIgnore Difficulty difficulty) {}

    public record Counts(int planned, long completed, long skipped) {
        static Counts from(List<Task> tasks) {
            return new Counts(tasks.size(), tasks.stream().filter(task -> task.state == TaskState.COMPLETED).count(),
                    tasks.stream().filter(task -> task.state == TaskState.SKIPPED).count());
        }
    }

    public record Day(LocalDate date, List<Task> items, Counts counts) {}
    public record Calendar(View view, LocalDate date, LocalDate today, LocalDate startDate, LocalDate endDate,
                           List<Day> days, Counts counts) {}
}
