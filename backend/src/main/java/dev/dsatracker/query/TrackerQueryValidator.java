package dev.dsatracker.query;

import static dev.dsatracker.query.TrackerQueryRequest.*;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TrackerQueryValidator {
    private final JsonMapper json = new JsonMapper();
    public static final int MAX_BYTES = 8192;
    public static final int MAX_PAGE = 10000;

    public enum Code { INVALID_QUERY, INVALID_DOMAIN, UNSUPPORTED_FILTER, INVALID_VALUE, MISSING_FIELD }
    public record Error(Code code, String message) {}
    public static final class InvalidQuery extends RuntimeException {
        private final Code code;
        InvalidQuery(Code code, String message) { super(message); this.code = code; }
        public Error error() { return new Error(code, getMessage()); }
    }

    public Filters parse(Domain domain, JsonNode query) {
        if (domain == null) throw invalid(Code.INVALID_DOMAIN, "Select a supported tracker domain.");
        if (query == null || !query.isObject() || json.writeValueAsBytes(query).length > MAX_BYTES) {
            throw invalid(Code.INVALID_QUERY, "Query must be an object of at most 8192 bytes.");
        }
        var type = type(domain);
        for (String name : query.propertyNames()) {
            var component = Arrays.stream(type.getRecordComponents()).filter(field -> field.getName().equals(name)).findFirst()
                    .orElseThrow(() -> invalid(Code.UNSUPPORTED_FILTER, "Unsupported filter for " + domain + "."));
            validateValue(name, query.get(name), component.getGenericType());
        }
        for (String name : required(domain)) {
            if (!query.has(name)) throw invalid(Code.MISSING_FIELD, name + " is required.");
        }
        var filters = json.treeToValue(query, type);
        int page = filters.page() == null ? 0 : filters.page();
        int limit = filters.limit() == null ? 20 : filters.limit();
        if (page < 0 || page > MAX_PAGE || limit < 1 || limit > 100) {
            throw invalid(Code.INVALID_VALUE, "Page must be 0–10000 and limit must be 1–100.");
        }
        switch (filters) {
            case Problems p -> {
                range(p.scheduledFrom(), p.scheduledTo(), 366);
                range(p.attemptedFrom(), p.attemptedTo(), 366);
                range(p.solvedFrom(), p.solvedTo(), 366);
            }
            case Goals g -> range(g.startDateFrom(), g.startDateTo(), 366);
            case Planner p -> range(p.startDate(), p.endDate(), 366);
            case Revisions r -> {
                range(r.startDate(), r.endDate(), 366);
                if (r.dueBefore() != null && r.status() != null && r.status() != dev.dsatracker.revision.RevisionDtos.Status.SCHEDULED)
                    throw invalid(Code.UNSUPPORTED_FILTER, "dueBefore requires scheduled revisions.");
            }
            case Analytics a -> {
                if (a.range() != null && !List.of("7","30","90","all").contains(a.range()))
                    throw invalid(Code.INVALID_VALUE, "Choose range 7, 30, 90, or all.");
                if (a.report() == Report.DASHBOARD_SUMMARY && (a.range() != null || a.page() != null || a.limit() != null))
                    throw invalid(Code.UNSUPPORTED_FILTER, "Dashboard summary takes no filters.");
                if ((a.report() == Report.SOLVING_ACTIVITY || a.report() == Report.RECENT_PROBLEMS
                        || a.report() == Report.DIFFICULTY_PROGRESS) && a.page() != null && a.page() != 0)
                    throw invalid(Code.UNSUPPORTED_FILTER, "This analytics report supports page zero only.");
                if ((a.report() == Report.SOLVING_ACTIVITY || a.report() == Report.DIFFICULTY_PROGRESS) && a.limit() != null)
                    throw invalid(Code.UNSUPPORTED_FILTER, "This analytics report has a fixed response limit.");
                if (a.report() == Report.RECENT_PROBLEMS && limit > 50)
                    throw invalid(Code.INVALID_VALUE, "Recent problems limit is at most 50 per list.");
            }
        }
        return filters;
    }

    private static void validateValue(String name, JsonNode node, Type type) {
        if (type instanceof ParameterizedType list) {
            if (!node.isArray() || node.isEmpty() || node.size() > 20)
                throw invalid(Code.INVALID_VALUE, name + " must contain 1–20 values.");
            node.forEach(item -> validateValue(name, item, list.getActualTypeArguments()[0]));
            return;
        }
        if (type == Boolean.class && node.isBoolean()) return;
        if (type == Integer.class && node.isIntegralNumber() && node.canConvertToInt()) return;
        if (type == Long.class && node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0) return;
        if (node.isString()) {
            var value = node.asString();
            if (type == String.class && !value.isBlank() && value.length() <= (name.startsWith("topic") ? 80 : 255)) return;
            if (type == LocalDate.class && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                try { if (LocalDate.parse(value).getYear() > 0) return; }
                catch (java.time.DateTimeException ignored) {}
            }
            if (type instanceof Class<?> cls && cls.isEnum()
                    && Arrays.stream(cls.getEnumConstants()).anyMatch(e -> ((Enum<?>) e).name().equals(value))) return;
        }
        throw invalid(Code.INVALID_VALUE, "Invalid value for " + name + ".");
    }

    private static void range(LocalDate from, LocalDate to, int maxDays) {
        if (from != null && to != null && (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= maxDays))
            throw invalid(Code.INVALID_VALUE, "Date windows must be ordered and at most " + maxDays + " days.");
    }

    static InvalidQuery invalid(Code code, String message) { return new InvalidQuery(code, message); }

    private static Class<? extends Filters> type(Domain domain) {
        return switch (domain) {
            case PROBLEMS -> Problems.class;
            case GOALS -> Goals.class;
            case PLANNER -> Planner.class;
            case REVISIONS -> Revisions.class;
            case ANALYTICS -> Analytics.class;
        };
    }

    private static List<String> required(Domain domain) {
        return switch (domain) {
            case PLANNER -> List.of("startDate", "endDate");
            case ANALYTICS -> List.of("report");
            default -> List.of();
        };
    }

    public static Map<String, Object> schema() {
        return Map.of("type", "object", "oneOf", Arrays.stream(Domain.values()).map(domain -> {
            var properties = new LinkedHashMap<String, Object>();
            for (var field : type(domain).getRecordComponents())
                properties.put(field.getName(), fieldSchema(field.getGenericType()));
            return Map.of("type","object","additionalProperties",false,"required",List.of("domain","query"),
                    "properties",Map.of("domain",Map.of("const",domain.name(),"type","string"),
                            "query",Map.of("type","object","additionalProperties",false,"required", required(domain),
                                    "properties",properties)));
        }).toList());
    }

    private static Map<String, Object> fieldSchema(Type type) {
        if (type instanceof ParameterizedType list)
            return Map.of("type","array","minItems",1,"maxItems",20,"items",fieldSchema(list.getActualTypeArguments()[0]));
        if (type == LocalDate.class) return Map.of("type","string","format","date");
        if (type == Boolean.class) return Map.of("type","boolean");
        if (type == Long.class || type == Integer.class) return Map.of("type","integer");
        if (type instanceof Class<?> cls && cls.isEnum())
            return Map.of("type","string","enum",Arrays.stream(cls.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList());
        return Map.of("type","string");
    }
}
