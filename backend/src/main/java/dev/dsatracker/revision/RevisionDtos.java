package dev.dsatracker.revision;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class RevisionDtos {
    private RevisionDtos() {}

    public enum Status { SCHEDULED, COMPLETED, CANCELLED }
    public enum Filter { DUE_TODAY, OVERDUE, UPCOMING, COMPLETED, CANCELLED }

    public record Schedule(@NotNull @Positive Long problemId, @NotNull LocalDate date) {}
    public record Reschedule(@NotNull LocalDate date) {}
    public record Task(long id, long problemId, long leetcodeId, String title, LocalDate scheduledDate,
                       Instant completedAt, Status status, Instant createdAt, Instant updatedAt) {}
    public record Counts(long dueToday, long overdue, long upcoming) {}
    public record Queue(LocalDate today, Counts counts, List<Task> items) {}
}
