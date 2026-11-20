package dev.dsatracker.exchange;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.CatalogDtos.ProblemInput;
import dev.dsatracker.catalog.CatalogDtos.ProgressPatch;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.LeetCodeLink;
import dev.dsatracker.catalog.ProblemRepository;
import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.catalog.Topic;
import dev.dsatracker.catalog.TopicService;
import dev.dsatracker.exchange.ExchangeDtos.Action;
import dev.dsatracker.exchange.ExchangeDtos.Format;
import dev.dsatracker.exchange.ExchangeDtos.ImportResult;
import dev.dsatracker.exchange.ExchangeDtos.Mode;
import dev.dsatracker.exchange.ExchangeDtos.Preview;
import dev.dsatracker.exchange.ExchangeDtos.RowPreview;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ExchangeService {
    private static final Logger log = LoggerFactory.getLogger(ExchangeService.class);
    private final ExchangeCodec codec;
    private final ProblemService problems;
    private final ProblemRepository catalog;
    private final TopicService topics;
    private final TransactionTemplate transaction;

    ExchangeService(ExchangeCodec codec, ProblemService problems, ProblemRepository catalog, TopicService topics,
                    PlatformTransactionManager manager) {
        this.codec = codec;
        this.problems = problems;
        this.catalog = catalog;
        this.topics = topics;
        transaction = new TransactionTemplate(manager);
    }

    byte[] export(Format format) {
        return codec.write(problems.exportCatalog(), format);
    }

    Preview preview(byte[] file, Format format, Mode mode) {
        var existing = new HashSet<>(catalog.leetcodeIds());
        var rows = validate(file, format).stream().map(row -> row.view(row.errors().isEmpty()
                ? action(existing.contains(row.id()), mode) : Action.INVALID, row.errors())).toList();
        return new Preview(rows.size(), count(rows, Action.CREATE), count(rows, Action.UPDATE),
                count(rows, Action.SKIP), count(rows, Action.INVALID), rows);
    }

    ImportResult apply(byte[] file, Format format, Mode mode) {
        var rows = validate(file, format);
        List<RowPreview> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (var row : rows) {
            if (!row.errors().isEmpty()) {
                errors.add(row.view(Action.INVALID, row.errors()));
                continue;
            }
            try {
                var action = transaction.execute(status -> save(row, mode));
                if (action == Action.CREATE) created++;
                if (action == Action.UPDATE) updated++;
                if (action == Action.SKIP) skipped++;
            } catch (ApiException failure) {
                var fields = failure.fieldErrors();
                errors.add(row.view(Action.INVALID, fields.isEmpty() ? Map.of("row", failure.getMessage()) : fields));
            } catch (DataAccessException | TransactionException failure) {
                log.warn("Catalog import failed for logical row {}", row.line(), failure);
                errors.add(row.view(Action.INVALID, Map.of("row", "The database rejected this change.")));
            }
        }
        return new ImportResult(rows.size(), created, updated, skipped, errors.size(), errors);
    }

    private Action save(Validated row, Mode mode) {
        var existing = catalog.findIdByLeetcodeId(row.id());
        var action = action(existing.isPresent(), mode);
        if (action == Action.SKIP) return action;
        var topicIds = row.topics().stream().map(topics::resolveId).toList();
        var input = new ProblemInput(row.id(), row.title(), row.url(), row.difficulty(), topicIds);
        if (action == Action.UPDATE) {
            problems.update(existing.orElseThrow(), input);
        } else {
            var problem = problems.create(input);
            if (row.progress() != null) problems.updateProgress(problem.id(), row.progress());
        }
        return action;
    }

    private List<Validated> validate(byte[] file, Format format) {
        var rows = codec.read(file, format).stream().map(this::validateRow).toList();
        Map<Long, Integer> occurrences = new HashMap<>();
        for (var row : rows) {
            if (row.id() != null) occurrences.merge(row.id(), 1, Integer::sum);
        }
        for (var row : rows) {
            if (row.id() != null && occurrences.get(row.id()) > 1) {
                row.errors().put("leetcodeId", "This LeetCode ID appears more than once in the file.");
            }
        }
        return rows;
    }

    private Validated validateRow(ExchangeCodec.Raw row) {
        Map<String, String> errors = new LinkedHashMap<>(row.errors());
        var fields = row.fields();
        if (errors.containsKey("row")) {
            return new Validated(row.line(), null, "", "", null, List.of(), null, errors);
        }
        String title = text(fields, "title");
        String url = text(fields, "url");
        Long id = parseId(text(fields, "leetcodeId"), errors);
        if (title.isEmpty() || title.length() > 255) errors.putIfAbsent("title", "Use a title from 1 to 255 characters.");
        if (url.length() > 2048) {
            errors.putIfAbsent("url", "Use a URL under 2,049 characters.");
        } else {
            try {
                url = LeetCodeLink.parse(url).url();
            } catch (ApiException failure) {
                errors.putIfAbsent("url", failure.getMessage());
            }
        }
        Difficulty difficulty = parseEnum(Difficulty.class, "difficulty", text(fields, "difficulty"), errors);
        var names = row.topics().stream().map(String::strip).toList();
        if (names.isEmpty() || names.size() > 100 || names.stream().anyMatch(name -> name.isEmpty() || name.length() > 80)) {
            errors.putIfAbsent("topics", "Select 1 to 100 non-empty topic names, each up to 80 characters.");
        }
        if (names.stream().map(Topic::comparisonKey).distinct().count() != names.size()) {
            errors.putIfAbsent("topics", "A topic is listed more than once.");
        }
        var patch = parseProgress(fields, errors);
        return new Validated(row.line(), id, title, url, difficulty, names, patch, errors);
    }

    private ProgressPatch parseProgress(Map<String, String> fields, Map<String, String> errors) {
        String statusText = text(fields, "status");
        String attempted = text(fields, "attemptedDate");
        String solved = text(fields, "solvedDate");
        String comments = fields.getOrDefault("comments", "");
        if (statusText.isEmpty() && attempted.isEmpty() && solved.isEmpty() && comments.isEmpty()) return null;

        var patch = new ProgressPatch();
        patch.setStatus(statusText.isEmpty() ? ProgressStatus.NOT_STARTED
                : parseEnum(ProgressStatus.class, "status", statusText, errors));
        patch.setAttemptedDate(parseDate("attemptedDate", attempted, errors));
        patch.setSolvedDate(parseDate("solvedDate", solved, errors));
        patch.setComments(comments);
        if (comments.length() > 10000) errors.put("comments", "Comments can contain at most 10,000 characters.");
        try {
            problems.validateImportedProgress(patch);
        } catch (ApiException failure) {
            failure.fieldErrors().forEach(errors::putIfAbsent);
        }
        return patch;
    }

    private static LocalDate parseDate(String field, String value, Map<String, String> errors) {
        if (value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException failure) {
            errors.put(field, "Use a valid YYYY-MM-DD date.");
            return null;
        }
    }

    private static Long parseId(String value, Map<String, String> errors) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        errors.putIfAbsent("leetcodeId", "Enter a positive whole-number LeetCode ID.");
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String value, Map<String, String> errors) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            errors.putIfAbsent(field, "Unrecognized " + field + ".");
            return null;
        }
    }

    private static String text(Map<String, String> fields, String key) {
        return fields.getOrDefault(key, "").strip();
    }

    private static Action action(boolean existing, Mode mode) {
        if (!existing) return Action.CREATE;
        return mode == Mode.SKIP_EXISTING ? Action.SKIP : Action.UPDATE;
    }

    private static long count(List<RowPreview> rows, Action action) {
        return rows.stream().filter(row -> row.action() == action).count();
    }

    private record Validated(int line, Long id, String title, String url, Difficulty difficulty,
                             List<String> topics, ProgressPatch progress, Map<String, String> errors) {
        RowPreview view(Action action, Map<String, String> messages) {
            return new RowPreview(line, id, title, action, messages);
        }
    }
}
