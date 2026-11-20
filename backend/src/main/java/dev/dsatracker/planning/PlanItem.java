package dev.dsatracker.planning;

import dev.dsatracker.catalog.DateColumns;
import dev.dsatracker.catalog.Problem;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "plan_items")
class PlanItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "problem_id", nullable = false, updatable = false)
    Problem problem;

    @Convert(converter = DateColumns.CalendarDate.class)
    @Column(name = "planned_date", nullable = false)
    LocalDate date;

    @Column(nullable = false)
    boolean skipped;

    protected PlanItem() {}

    PlanItem(Problem problem, LocalDate date) {
        this.problem = problem;
        this.date = date;
    }
}
