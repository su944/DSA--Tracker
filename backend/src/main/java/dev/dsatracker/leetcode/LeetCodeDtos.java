package dev.dsatracker.leetcode;

import dev.dsatracker.catalog.Difficulty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LeetCodeDtos {
    private LeetCodeDtos() {}

    public enum StopReason { RESULT_LIMIT, CATALOG_EXHAUSTED, PAGE_LIMIT, TIME_LIMIT }

    record Metadata(long leetcodeId, String title, String slug, String url, Difficulty difficulty,
                    List<String> topics, boolean paidOnly) {
        ProblemItem withCatalogStatus(boolean alreadyImported) {
            return new ProblemItem(leetcodeId, title, slug, url, difficulty, topics, paidOnly, alreadyImported);
        }
    }

    public record ProblemItem(long leetcodeId, String title, String slug, String url, Difficulty difficulty,
                              List<String> topics, boolean paidOnly, boolean alreadyImported) {}

    public record SearchResult(List<ProblemItem> items, int page, boolean hasMore, StopReason stopReason) {}

    public record Preview(UUID previewId, int requestedCount, int returnedCount, int scannedCount,
                          int skippedExisting, int skippedInvalid, List<ProblemItem> candidates,
                          Instant expirationTime, StopReason stopReason) {}

    public record ImportResult(int imported, int skipped, int failed, List<ImportFailure> failures) {}

    public record ImportFailure(long leetcodeId, String message) {}
}
