package dev.dsatracker.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

public final class DateColumns {
    private DateColumns() {}

    @Converter
    public static class Timestamp implements AttributeConverter<Instant, String> {
        private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

        @Override
        public String convertToDatabaseColumn(Instant value) {
            return value == null ? null : FORMAT.format(value);
        }

        @Override
        public Instant convertToEntityAttribute(String value) {
            return value == null ? null : Instant.parse(value);
        }
    }

    @Converter
    public static class CalendarDate implements AttributeConverter<LocalDate, String> {
        @Override
        public String convertToDatabaseColumn(LocalDate value) {
            return value == null ? null : value.toString();
        }

        @Override
        public LocalDate convertToEntityAttribute(String value) {
            return value == null ? null : LocalDate.parse(value);
        }
    }
}
