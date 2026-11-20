package dev.dsatracker.catalog;

import dev.dsatracker.api.ApiException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

record ProblemQuery(String search, List<Long> topics, Difficulty difficulty, ProgressStatus status,
                    int page, int size, String sort) {
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
            if (difficulty != null) {
                filters.add(criteria.equal(problem.get("difficulty"), difficulty));
            }
            if (status != null) {
                filters.add(criteria.equal(problem.get("progress").get("status"), status));
            }
            return criteria.and(filters);
        };
    }

    private static Long tryId(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
