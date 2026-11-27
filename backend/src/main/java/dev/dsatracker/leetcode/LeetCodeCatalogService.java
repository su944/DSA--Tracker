package dev.dsatracker.leetcode;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.cache.ExpiringCache;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.catalog.Topic;
import dev.dsatracker.leetcode.LeetCodeDtos.Metadata;
import dev.dsatracker.leetcode.LeetCodeDtos.Preview;
import dev.dsatracker.leetcode.LeetCodeDtos.ProblemItem;
import dev.dsatracker.leetcode.LeetCodeDtos.SearchResult;
import dev.dsatracker.leetcode.LeetCodeDtos.StopReason;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class LeetCodeCatalogService {
    private static final int REMOTE_PAGE_SIZE = 100;
    private static final Duration METADATA_TTL = Duration.ofMinutes(10);

    private final LeetCodeClient client;
    private final ProblemService local;
    private final LeetCodeSettings settings;
    private final Clock clock;
    private final ExpiringCache<Long, Metadata> metadata;
    private final ExpiringCache<UUID, Preview> previews;

    LeetCodeCatalogService(LeetCodeClient client, ProblemService local, LeetCodeSettings settings, Clock clock) {
        this.client = client;
        this.local = local;
        this.settings = settings;
        this.clock = clock;
        this.metadata = new ExpiringCache<>(10_000, clock);
        this.previews = new ExpiringCache<>(16, clock);
    }

    public SearchResult search(String query, List<String> topicNames, Difficulty difficulty,
                               boolean includePremium, int limit, int page) {
        requireRange("limit", limit, 100);
        long offset = (long) page * limit;
        if (page < 0 || offset >= (long) REMOTE_PAGE_SIZE * settings.maxPages()) {
            throw ApiException.invalid("page", "Page is outside the configured LeetCode scan bounds.");
        }
        var filter = Filter.from(query, topicNames, difficulty, includePremium);
        var matches = new ArrayList<Metadata>();
        var scan = scan(problem -> {
            if (filter.matches(problem)) matches.add(problem);
            return matches.size() > offset + limit;
        });
        var existing = local.existingLeetcodeIds();
        var items = matches.stream().skip(offset).limit(limit)
                .map(problem -> problem.withCatalogStatus(existing.contains(problem.leetcodeId()))).toList();
        return new SearchResult(items, page, matches.size() > offset + limit, scan.stop());
    }

    public ProblemItem get(long leetcodeId) {
        if (leetcodeId < 1) {
            throw ApiException.invalid("leetcodeId", "LeetCode ID must be a positive integer.");
        }
        var cached = metadata.get(leetcodeId);
        if (cached.isEmpty()) {
            var scan = scan(problem -> problem.leetcodeId() == leetcodeId);
            if (scan.stop() == StopReason.RESULT_LIMIT) {
                cached = Optional.of(scan.match());
            } else if (scan.stop() == StopReason.CATALOG_EXHAUSTED) {
                throw ApiException.missing("LeetCode problem " + leetcodeId + " was not found in the remote catalog.");
            } else {
                throw new IllegalStateException("Could not verify LeetCode problem " + leetcodeId
                        + " within the remote scan budget (" + scan.stop() + ").");
            }
        }
        return cached.orElseThrow().withCatalogStatus(local.existingLeetcodeIds().contains(leetcodeId));
    }

    public Preview preview(int count, List<String> topicNames, Difficulty difficulty, boolean includePremium) {
        requireRange("count", count, 500);
        var filter = Filter.from(null, topicNames, difficulty, includePremium);
        var existing = local.existingLeetcodeIds();
        var candidates = new ArrayList<ProblemItem>();
        int[] skippedExisting = {0};
        var scan = scan(problem -> {
            if (existing.contains(problem.leetcodeId())) {
                skippedExisting[0]++;
            } else if (filter.matches(problem)) {
                candidates.add(problem.withCatalogStatus(false));
            }
            return candidates.size() == count;
        });
        var id = UUID.randomUUID();
        var expiration = clock.instant().plus(settings.previewTtl());
        var preview = new Preview(id, count, candidates.size(), scan.scanned(), skippedExisting[0],
                scan.invalid(), List.copyOf(candidates), expiration, scan.stop());
        previews.put(id, preview, expiration);
        return preview;
    }

    Optional<Preview> cachedPreview(UUID id) {
        return previews.get(id);
    }

    private Scan scan(Predicate<Metadata> stopWhen) {
        long deadline = System.nanoTime() + settings.scanTimeout().toNanos();
        int scanned = 0;
        int invalid = 0;
        var seen = new HashSet<Long>();
        for (int page = 0; page < settings.maxPages(); page++) {
            var response = client.fetch(REMOTE_PAGE_SIZE, scanned, deadline);
            if (response.isEmpty()) return new Scan(scanned, invalid, StopReason.TIME_LIMIT, null);
            var batch = response.orElseThrow();
            for (var row : batch.questions()) {
                scanned++;
                var mapped = LeetCodeMapper.map(row);
                if (mapped.isEmpty()) {
                    invalid++;
                    continue;
                }
                var problem = mapped.orElseThrow();
                if (seen.add(problem.leetcodeId())) {
                    metadata.put(problem.leetcodeId(), problem, clock.instant().plus(METADATA_TTL));
                    if (stopWhen.test(problem)) return new Scan(scanned, invalid, StopReason.RESULT_LIMIT, problem);
                }
            }
            if (batch.questions().isEmpty() || scanned >= batch.total()) {
                return new Scan(scanned, invalid, StopReason.CATALOG_EXHAUSTED, null);
            }
        }
        return new Scan(scanned, invalid, StopReason.PAGE_LIMIT, null);
    }

    private static void requireRange(String field, int value, int max) {
        if (value < 1 || value > max) {
            throw ApiException.invalid(field, field + " must be between 1 and " + max + ".");
        }
    }

    private record Scan(int scanned, int invalid, StopReason stop, Metadata match) {}

    private record Filter(String query, Set<String> topics, Difficulty difficulty, boolean includePremium) {
        static Filter from(String query, List<String> topics, Difficulty difficulty, boolean includePremium) {
            if (query != null && query.length() > 255) {
                throw ApiException.invalid("query", "Title search must be at most 255 characters.");
            }
            var requested = topics == null ? List.<String>of() : topics;
            if (requested.size() > 100 || requested.stream()
                    .anyMatch(topic -> topic == null || topic.isBlank() || topic.strip().length() > 80)) {
                throw ApiException.invalid("topicNames", "Provide up to 100 nonblank topic names of at most 80 characters.");
            }
            return new Filter(query == null ? "" : query.strip().toLowerCase(Locale.ROOT),
                    requested.stream().map(Topic::comparisonKey).collect(Collectors.toUnmodifiableSet()),
                    difficulty, includePremium);
        }

        boolean matches(Metadata problem) {
            return (includePremium || !problem.paidOnly())
                    && (difficulty == null || difficulty == problem.difficulty())
                    && problem.title().toLowerCase(Locale.ROOT).contains(query)
                    && (topics.isEmpty() || problem.topics().stream().map(Topic::comparisonKey).anyMatch(topics::contains));
        }
    }
}
