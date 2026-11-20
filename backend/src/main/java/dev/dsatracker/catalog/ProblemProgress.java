package dev.dsatracker.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "problem_progress")
class ProblemProgress {
    @Id
    @Column(name = "problem_id")
    Long problemId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id")
    Problem problem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    ProgressStatus status;

    @Convert(converter = DateColumns.CalendarDate.class)
    @Column(name = "attempted_date")
    LocalDate attemptedDate;

    @Convert(converter = DateColumns.CalendarDate.class)
    @Column(name = "solved_date")
    LocalDate solvedDate;

    @Column(nullable = false)
    String comments;

    @Convert(converter = DateColumns.Timestamp.class)
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected ProblemProgress() {}

    ProblemProgress(Problem problem, Instant now) {
        this.problem = problem;
        status = ProgressStatus.NOT_STARTED;
        comments = "";
        updatedAt = now;
    }

    void edit(ProgressStatus status, LocalDate attemptedDate, LocalDate solvedDate, String comments, Instant now) {
        this.status = status;
        this.attemptedDate = attemptedDate;
        this.solvedDate = solvedDate;
        this.comments = comments;
        updatedAt = now;
    }
}
