package dev.dsatracker.reporting;

import dev.dsatracker.api.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

record ReportingPeriod(LocalDate start, LocalDate today, ZoneId zone) {
    static ReportingPeriod parse(String range, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = switch (range) {
            case "7" -> today.minusDays(6);
            case "30" -> today.minusDays(29);
            case "90" -> today.minusDays(89);
            case "all" -> null;
            default -> throw ApiException.invalid("range", "Choose 7, 30, 90, or all.");
        };
        return new ReportingPeriod(start, today, clock.getZone());
    }

    boolean allTime() {
        return start == null;
    }

    LocalDate firstDatedSolve() {
        return allTime() ? LocalDate.of(1, 1, 1) : start;
    }
}
