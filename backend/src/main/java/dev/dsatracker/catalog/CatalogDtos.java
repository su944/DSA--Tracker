package dev.dsatracker.catalog;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class CatalogDtos {
    private CatalogDtos() {}

    public record ProblemInput(
            @NotNull @Positive Long leetcodeId,
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 2048) String url,
            @NotNull Difficulty difficulty,
            @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> topicIds) {}

    public record TopicInput(@NotBlank @Size(max = 80) String name) {}

    public record TopicView(long id, String name, Instant createdAt) {
        static TopicView from(Topic topic) {
            return new TopicView(topic.id, topic.name, topic.createdAt);
        }
    }

    public record ProgressView(ProgressStatus status, LocalDate attemptedDate, LocalDate solvedDate,
                               String comments, Instant updatedAt) {
        static ProgressView from(ProblemProgress progress) {
            return new ProgressView(progress.status, progress.attemptedDate, progress.solvedDate,
                    progress.comments, progress.updatedAt);
        }
    }

    public record ProblemView(long id, long leetcodeId, String title, String slug, String url, Difficulty difficulty,
                              Instant createdAt, Instant updatedAt, List<TopicView> topics, ProgressView progress) {
        static ProblemView from(Problem problem) {
            var topics = problem.topics.stream().sorted(Comparator.comparing(topic -> topic.nameKey))
                    .map(TopicView::from).toList();
            return new ProblemView(problem.id, problem.leetcodeId, problem.title, problem.slug, problem.url,
                    problem.difficulty, problem.createdAt, problem.updatedAt, topics, ProgressView.from(problem.progress));
        }
    }

    public record ProblemPage(List<ProblemView> items, int page, int size, long totalItems, int totalPages) {}

    public static final class ProgressPatch {
        private ProgressStatus status;
        private LocalDate attemptedDate;
        private LocalDate solvedDate;
        @Size(max = 10000)
        private String comments;
        private boolean attemptedDateProvided;
        private boolean solvedDateProvided;

        @JsonSetter(nulls = Nulls.FAIL)
        public void setStatus(ProgressStatus status) { this.status = status; }

        public void setAttemptedDate(LocalDate attemptedDate) {
            this.attemptedDate = attemptedDate;
            attemptedDateProvided = true;
        }

        public void setSolvedDate(LocalDate solvedDate) {
            this.solvedDate = solvedDate;
            solvedDateProvided = true;
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setComments(String comments) { this.comments = comments; }

        public ProgressStatus status() { return status; }
        public LocalDate attemptedDate() { return attemptedDate; }
        public LocalDate solvedDate() { return solvedDate; }
        public String comments() { return comments; }
        public boolean attemptedDateProvided() { return attemptedDateProvided; }
        public boolean solvedDateProvided() { return solvedDateProvided; }
    }
}
