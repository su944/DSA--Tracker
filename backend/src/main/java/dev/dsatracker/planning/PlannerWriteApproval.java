package dev.dsatracker.planning;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.cache.ExpiringCache;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
public class PlannerWriteApproval {
    private final Clock clock;
    private final ExpiringCache<Action, Boolean> approvals;

    PlannerWriteApproval(Clock clock) {
        this.clock = clock;
        this.approvals = new ExpiringCache<>(64, clock);
    }

    void authorize(Action action) {
        PlanningDates.requirePositiveId(action.id());
        PlanningDates.checked("date", action.date());
        approvals.put(action, true, clock.instant().plusSeconds(300));
    }

    public void require(Operation operation, long id, LocalDate date) {
        if (!approvals.consume(new Action(operation, id, date))) {
            throw ApiException.conflict("approval", "Local operator approval is required for this exact planner action.");
        }
    }

    public enum Operation { SCHEDULE, RESCHEDULE }

    record Action(Operation operation, long id, LocalDate date) {}
}
