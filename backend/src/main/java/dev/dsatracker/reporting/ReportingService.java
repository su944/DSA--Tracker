package dev.dsatracker.reporting;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.planning.GoalService;
import dev.dsatracker.planning.PlannerDtos.View;
import dev.dsatracker.planning.PlannerService;
import dev.dsatracker.reporting.ReportingDtos.Activity;
import dev.dsatracker.reporting.ReportingDtos.ActivityDay;
import dev.dsatracker.reporting.ReportingDtos.DifficultyStats;
import dev.dsatracker.reporting.ReportingDtos.Recent;
import dev.dsatracker.reporting.ReportingDtos.Summary;
import dev.dsatracker.reporting.ReportingDtos.Today;
import dev.dsatracker.reporting.ReportingDtos.TopicStats;
import dev.dsatracker.revision.RevisionService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportingService {
    private final ReportingRepository reports;
    private final GoalService goals;
    private final PlannerService planner;
    private final Clock clock;
    private final RevisionService revisions;

    ReportingService(ReportingRepository reports, GoalService goals, PlannerService planner, Clock clock, RevisionService revisions) {
        this.reports = reports;
        this.goals = goals;
        this.planner = planner;
        this.clock = clock;
        this.revisions = revisions;
    }

    public Summary summary() {
        return reports.summary();
    }

    @Transactional(readOnly = true)
    Today today() {
        LocalDate date = LocalDate.now(clock);
        var plan = planner.calendar(date, View.DAY).days().getFirst();
        return new Today(date, plan, goals.activeOn(date), revisions.counts(date));
    }

    public List<TopicStats> topics(String range) {
        return reports.topics(ReportingPeriod.parse(range, clock));
    }

    public List<DifficultyStats> difficulty(String range) {
        return reports.difficulty(ReportingPeriod.parse(range, clock));
    }

    public Activity activity(String range) {
        var period = ReportingPeriod.parse(range, clock);
        var days = reports.activity(period);
        if (!period.allTime() && !days.isEmpty()) {
            var solved = days.stream().collect(Collectors.toMap(ActivityDay::date, ActivityDay::solved));
            days = period.start().datesUntil(period.today().plusDays(1))
                    .map(date -> new ActivityDay(date, solved.getOrDefault(date, 0L))).toList();
        }
        return new Activity(period.start(), period.today(), days);
    }

    @Transactional(readOnly = true)
    public Recent recent(String range, int limit) {
        if (limit < 1 || limit > 50) {
            throw ApiException.invalid("limit", "Recent list size must be from 1 to 50.");
        }
        var period = ReportingPeriod.parse(range, clock);
        return new Recent(reports.recentlySolved(period, limit), reports.recentlyModified(period, limit));
    }
}
