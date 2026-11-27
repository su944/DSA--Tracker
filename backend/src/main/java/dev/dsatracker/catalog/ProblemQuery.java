package dev.dsatracker.catalog;

import dev.dsatracker.api.ApiException;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import dev.dsatracker.planning.PlanItem;
import dev.dsatracker.query.TrackerQueryRequest.Problems;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

record ProblemQuery(String search, List<Long> topics, Difficulty difficulty, ProgressStatus status,
                    int page, int size, String sort, Long exactLeetcodeId) {
    ProblemQuery(String search, List<Long> topics, Difficulty difficulty, ProgressStatus status,
                 int page, int size, String sort) {
        this(search, topics, difficulty, status, page, size, sort, null);
    }

    private static final Set<String> SORT_FIELDS = Set.of("id", "leetcodeId", "title", "createdAt", "updatedAt");

    PageRequest pageRequest() {
        if (size < 1 || size > 100) {
            throw ApiException.invalid("size", "Page size must be from 1 to 100.");
        }
        if (page < 0 || (long) page * size > Integer.MAX_VALUE) {
            throw ApiException.invalid("page", "Page must be a supported non-negative index.");
        }
        String[] order = sort.split(",", -1);
        if (order.length != 2 || !SORT_FIELDS.contains(order[0])
                || !Set.of("asc", "desc").contains(order[1])) {
            throw ApiException.invalid("sort", "Choose a supported sort field and asc or desc.");
        }
        Sort ordering = Sort.by(Sort.Direction.fromString(order[1]), order[0]);
        if (!order[0].equals("id")) {
            ordering = ordering.and(Sort.by("id"));
        }
        return PageRequest.of(page, size, ordering);
    }

    Specification<Problem> specification() {
        if (topics != null && topics.stream().anyMatch(id -> id == null || id <= 0)) {
            throw ApiException.invalid("topic", "Topic IDs must be positive.");
        }
        if (search.length() > 255) {
            throw ApiException.invalid("q", "Search must be at most 255 characters.");
        }
        String queryText = search.strip().toLowerCase(Locale.ROOT);
        Long leetcodeId = queryText.isEmpty() ? null : tryId(queryText);

        return (problem, query, criteria) -> {
            List<Predicate> filters = new ArrayList<>();
            if (!queryText.isEmpty()) {
                String escaped = queryText.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                Predicate title = criteria.like(criteria.lower(problem.get("title")), "%" + escaped + "%", '\\');
                filters.add(leetcodeId == null ? title : criteria.or(title, criteria.equal(problem.get("leetcodeId"), leetcodeId)));
            }
            if (topics != null && !topics.isEmpty()) {
                var matches = query.subquery(Long.class);
                var tagged = matches.correlate(problem).join("topics");
                matches.select(tagged.get("id")).where(tagged.get("id").in(topics));
                filters.add(criteria.exists(matches));
            }
            if (exactLeetcodeId != null) {
                filters.add(criteria.equal(problem.get("leetcodeId"), exactLeetcodeId));
            }
            if (difficulty != null) {
                filters.add(criteria.equal(problem.get("difficulty"), difficulty));
            }
            if (status != null) {
                filters.add(criteria.equal(problem.get("progress").get("status"), status));
            }
            return criteria.and(filters);
        };
    }

    static Specification<Problem> trackerFilters(Problems filters) {
        return (problem, query, criteria) -> {
            var conditions = new ArrayList<Predicate>();
            if (filters.title() != null) {
                String text = filters.title().strip().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                conditions.add(criteria.like(criteria.lower(problem.get("title")), "%" + text + "%", '\\'));
            }
            var progress = problem.get("progress");
            if (filters.statuses() != null) conditions.add(progress.get("status").in(filters.statuses()));
            dates(conditions, criteria, progress.get("attemptedDate"), filters.attemptedFrom(), filters.attemptedTo());
            dates(conditions, criteria, progress.get("solvedDate"), filters.solvedFrom(), filters.solvedTo());
            boolean exclude = Boolean.TRUE.equals(filters.excludeScheduled());
            if (exclude || filters.scheduledFrom() != null || filters.scheduledTo() != null) {
                var sub = query.subquery(Long.class);
                var item = sub.from(PlanItem.class);
                var scheduled = new ArrayList<Predicate>();
                scheduled.add(criteria.equal(item.get("problem").get("id"), problem.get("id")));
                dates(scheduled, criteria, item.get("date"), filters.scheduledFrom(), filters.scheduledTo());
                sub.select(item.get("id")).where(scheduled.toArray(Predicate[]::new));
                var exists = criteria.exists(sub);
                conditions.add(exclude ? criteria.not(exists) : exists);
            }
            return criteria.and(conditions);
        };
    }

    private static void dates(List<Predicate> conditions, CriteriaBuilder criteria, Path<LocalDate> path,
                              LocalDate from, LocalDate to) {
        if (from != null) conditions.add(criteria.greaterThanOrEqualTo(path, from));
        if (to != null) conditions.add(criteria.lessThanOrEqualTo(path, to));
    }

    private static Long tryId(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
