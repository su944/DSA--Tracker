package dev.dsatracker.leetcode;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.leetcode.LeetCodeDtos.ImportFailure;
import dev.dsatracker.leetcode.LeetCodeDtos.ImportResult;
import dev.dsatracker.leetcode.LeetCodeDtos.ProblemItem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class LeetCodeImportService {
    private static final Logger log = LoggerFactory.getLogger(LeetCodeImportService.class);

    private final LeetCodeCatalogService discovery;
    private final ProblemService catalog;

    LeetCodeImportService(LeetCodeCatalogService discovery, ProblemService catalog) {
        this.discovery = discovery;
        this.catalog = catalog;
    }

    public ImportResult importSelection(UUID previewId, List<Long> leetcodeIds) {
        var preview = discovery.cachedPreview(previewId)
                .orElseThrow(() -> ApiException.missing("LeetCode preview was not found or has expired. Create a new preview."));
        if (leetcodeIds == null || leetcodeIds.isEmpty() || leetcodeIds.size() > 500
                || leetcodeIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw ApiException.invalid("leetcodeIds", "Select between 1 and 500 positive LeetCode IDs.");
        }
        var selected = new LinkedHashSet<>(leetcodeIds);
        var candidates = preview.candidates().stream()
                .collect(Collectors.toMap(ProblemItem::leetcodeId, Function.identity()));
        for (long id : selected) {
            if (!candidates.containsKey(id)) {
                throw ApiException.invalid("leetcodeIds", "LeetCode ID " + id + " is not a candidate in this preview.");
            }
        }

        int imported = 0;
        int skipped = 0;
        var failures = new ArrayList<ImportFailure>();
        for (long id : selected) {
            var item = candidates.get(id);
            try {
                if (catalog.importIfMissing(id, item.title(), item.url(), item.difficulty(), item.topics())) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (ApiException failure) {
                failures.add(new ImportFailure(id, failure.getMessage()));
            } catch (DataAccessException | TransactionException failure) {
                // The item transaction has rolled back. A competing insert may now own this ID.
                if (alreadyImported(id)) {
                    skipped++;
                } else {
                    log.warn("LeetCode preview import failed for problem {}", id, failure);
                    failures.add(new ImportFailure(id, "The database rejected this import."));
                }
            }
        }
        return new ImportResult(imported, skipped, failures.size(), List.copyOf(failures));
    }

    private boolean alreadyImported(long id) {
        try {
            return catalog.containsLeetcodeId(id);
        } catch (DataAccessException | TransactionException failure) {
            return false;
        }
    }
}
