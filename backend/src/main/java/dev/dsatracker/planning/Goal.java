package dev.dsatracker.planning;

import dev.dsatracker.catalog.DateColumns;
import dev.dsatracker.catalog.Topic;
import dev.dsatracker.planning.GoalDtos.GoalType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "goals")
public class Goal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    GoalType type;

    @Column(name = "start_date", nullable = false)
    @Convert(converter = DateColumns.CalendarDate.class)
    LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    @Convert(converter = DateColumns.CalendarDate.class)
    LocalDate endDate;

    @Column(name = "target_count", nullable = false)
    int targetCount;

    @ManyToOne
    @JoinColumn(name = "topic_id")
    Topic topic;

    protected Goal() {}

    void edit(GoalDtos.GoalInput input, LocalDate end, Topic topic) {
        name = input.name().strip();
        type = input.type();
        startDate = input.startDate();
        endDate = end;
        targetCount = input.targetCount();
        this.topic = topic;
    }
}
