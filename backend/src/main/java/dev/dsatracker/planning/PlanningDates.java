package dev.dsatracker.planning;

import dev.dsatracker.api.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

final class PlanningDates {
    private PlanningDates() {}

    static LocalDate checked(String field, LocalDate date) {
        if (date.getYear() < 1 || date.getYear() > 9999) {
            throw ApiException.invalid(field, "Enter a date with a year from 0001 to 9999.");
        }
        return date;
    }

    static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static void requirePositiveId(long id) {
        if (id <= 0) {
            throw ApiException.invalid("id", "ID must be positive.");
        }
    }
}
