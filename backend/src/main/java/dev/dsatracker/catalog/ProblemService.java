package dev.dsatracker.catalog;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.CatalogDtos.ProblemInput;
import dev.dsatracker.catalog.CatalogDtos.ProblemPage;
import dev.dsatracker.catalog.CatalogDtos.ProblemView;
import dev.dsatracker.catalog.CatalogDtos.ProgressPatch;
import dev.dsatracker.catalog.CatalogDtos.ProgressView;
import dev.dsatracker.revision.RevisionService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProblemService {
    private final ProblemRepository problems;
    private final TopicRepository topics;
    private final TopicService topicService;
    private final Clock clock;
    private final RevisionService revisions;

    ProblemService(ProblemRepository problems, TopicRepository topics, TopicService topicService,
                   Clock clock, RevisionService revisions) {
        this.problems = problems;
        this.topics = topics;
        this.topicService = topicService;
        this.clock = clock;
        this.revisions = revisions;
    }

    @Transactional(readOnly = true)
    ProblemPage list(ProblemQuery query) {
        var page = problems.findAll(query.specification(), query.pageRequest());
        return new ProblemPage(page.map(ProblemView::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProblemPage searchCatalog(String query, Long leetcodeId, List<String> topicNames, Difficulty difficulty,
                                     ProgressStatus status, int page, int size) {
        if (leetcodeId != null && leetcodeId <= 0) {
            throw ApiException.invalid("leetcodeId", "LeetCode ID must be positive.");
        }
        if (query != null && query.length() > 255) {
            throw ApiException.invalid("query", "Search must be at most 255 characters.");
        }

        boolean filterByTopics = topicNames != null && !topicNames.isEmpty();
        List<Long> topicIds = topicService.findIdsByNames(topicNames);

        var filters = new ProblemQuery(query == null ? "" : query, topicIds, difficulty, status,
                page, size, "leetcodeId,asc", leetcodeId);
        if (filterByTopics && topicIds.isEmpty()) {
            filters.pageRequest();
            return new ProblemPage(List.of(), page, size, 0, 0);
        }
        return list(filters);
    }

    @Transactional(readOnly = true)
    public ProblemView getByLeetcodeId(long leetcodeId) {
        return get(requireIdByLeetcodeId(leetcodeId));
    }

    @Transactional(readOnly = true)
    public long requireIdByLeetcodeId(long leetcodeId) {
        if (leetcodeId <= 0) {
            throw ApiException.invalid("leetcodeId", "LeetCode ID must be positive.");
        }
        return problems.findIdByLeetcodeId(leetcodeId)
                .orElseThrow(() -> ApiException.missing("No problem found with LeetCode ID " + leetcodeId + "."));
    }

    @Transactional
    public ProgressView updateProgressByLeetcodeId(long leetcodeId, ProgressPatch patch) {
        return updateProgress(requireIdByLeetcodeId(leetcodeId), patch);
    }

    @Transactional(readOnly = true)
    ProblemView get(long id) {
        return ProblemView.from(requireProblem(id));
    }

    @Transactional
    public boolean importIfMissing(long leetcodeId, String title, String url, Difficulty difficulty,
                                   List<String> topicNames) {
        if (problems.existsByLeetcodeId(leetcodeId)) {
            return false;
        }
        if (topicNames.isEmpty() || topicNames.size() > 100) {
            throw ApiException.invalid("topics", "Import requires between 1 and 100 topics.");
        }
        var topicIds = topicNames.stream().map(topicService::resolveId).toList();
        create(new ProblemInput(leetcodeId, title, url, difficulty, topicIds));
        return true;
    }

    @Transactional(readOnly = true)
    public boolean containsLeetcodeId(long leetcodeId) {
        return problems.existsByLeetcodeId(leetcodeId);
    }

    @Transactional
    public ProblemView create(ProblemInput input) {
        if (problems.existsByLeetcodeId(input.leetcodeId())) {
            throw duplicateId();
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var problem = new Problem(now);
        problem.edit(input, LeetCodeLink.parse(input.url()), requireTopics(input.topicIds()), now);
        return ProblemView.from(problems.save(problem));
    }

    @Transactional
    public ProblemView update(long id, ProblemInput input) {
        var problem = requireProblem(id);
        if (problems.existsByLeetcodeIdAndIdNot(input.leetcodeId(), id)) {
            throw duplicateId();
        }
        problem.edit(input, LeetCodeLink.parse(input.url()), requireTopics(input.topicIds()),
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return ProblemView.from(problem);
    }

    @Transactional
    void delete(long id) {
        problems.delete(requireProblem(id));
    }

    @Transactional
    public ProgressView updateProgress(long id, ProgressPatch patch) {
        var progress = requireProblem(id).progress;
        var next = resolveProgress(patch, progress.status, progress.attemptedDate, progress.solvedDate);
        String comments = patch.comments() == null ? progress.comments : patch.comments();
        var before = progress.status;
        var oldSolved = progress.solvedDate;
        progress.edit(next.status(), next.attempted(), next.solved(), comments, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        revisions.reconcile(id, before, oldSolved, next.status(), next.solved());
        return ProgressView.from(progress);
    }

    public void validateImportedProgress(ProgressPatch patch) {
        resolveProgress(patch, ProgressStatus.NOT_STARTED, null, null);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Slice<QueryProblem> query(
            dev.dsatracker.query.TrackerQueryRequest.Problems filters, int page, int limit) {
        var topics = topicService.findIdsByNames(filters.topicNames());
        var base = new ProblemQuery("", topics, filters.difficulty(), null, page, limit,
                (filters.sortBy() == null ? "leetcodeId" : filters.sortBy().property()) + ","
                        + (filters.sortDirection() == dev.dsatracker.query.TrackerQueryRequest.Direction.DESC ? "desc" : "asc"),
                filters.leetcodeId());
        var pageable = base.pageRequest();
        if (filters.topicNames() != null && topics.isEmpty()) {
            return new org.springframework.data.domain.SliceImpl<>(List.of(), pageable, false);
        }
        var spec = base.specification().and(ProblemQuery.trackerFilters(filters));
        return problems.findBy(spec, query -> query.slice(pageable))
                .map(problem -> new QueryProblem(problem.leetcodeId, problem.title, problem.difficulty, problem.progress.status));
    }

    public record QueryProblem(long leetcodeId, String title, Difficulty difficulty, ProgressStatus status) {}

    @Transactional(readOnly = true)
    public Set<Long> existingLeetcodeIds() {
        return Set.copyOf(problems.leetcodeIds());
    }

    @Transactional(readOnly = true)
    public List<ProblemView> exportCatalog() {
        return problems.forExport().stream().map(ProblemView::from).toList();
    }

    private record ProgressValues(ProgressStatus status, LocalDate attempted, LocalDate solved) {}

    private ProgressValues resolveProgress(ProgressPatch patch, ProgressStatus current,
                                           LocalDate previousAttempt, LocalDate previousSolve) {
        var status = patch.status() == null ? current : patch.status();
        LocalDate today = LocalDate.now(clock);
        LocalDate attempted = patch.attemptedDateProvided() ? patch.attemptedDate() : previousAttempt;
        LocalDate solved = patch.solvedDateProvided() ? patch.solvedDate() : previousSolve;
        if (status == ProgressStatus.NOT_STARTED) {
            rejectIncompatibleDate("attemptedDate", patch.attemptedDate());
            attempted = null;
        } else if (status == ProgressStatus.ATTEMPTED) {
            attempted = requiredDate("attemptedDate", attempted, patch.attemptedDateProvided(), today);
        }
        if (status == ProgressStatus.SOLVED) {
            solved = requiredDate("solvedDate", solved, patch.solvedDateProvided(), today);
        } else {
            rejectIncompatibleDate("solvedDate", patch.solvedDate());
            solved = null;
        }
        validateDate("attemptedDate", attempted, today);
        validateDate("solvedDate", solved, today);
        if (attempted != null && solved != null && solved.isBefore(attempted)) {
            throw ApiException.invalid("solvedDate", "Solved date must be on or after attempted date.");
        }
        return new ProgressValues(status, attempted, solved);
    }

    private static LocalDate requiredDate(String field, LocalDate value, boolean provided, LocalDate today) {
        if (value != null) {
            return value;
        }
        if (provided) {
            throw ApiException.invalid(field, "A date is required for the selected status.");
        }
        return today;
    }

    private static void validateDate(String field, LocalDate value, LocalDate today) {
        if (value != null && (value.getYear() < 1 || value.isAfter(today))) {
            throw ApiException.invalid(field, "Enter a calendar date no later than today.");
        }
    }

    private static void rejectIncompatibleDate(String field, LocalDate value) {
        if (value != null) {
            throw ApiException.invalid(field, "This date is unavailable for the selected status.");
        }
    }

    private Problem requireProblem(long id) {
        if (id <= 0) {
            throw ApiException.invalid("id", "Problem ID must be positive.");
        }
        return problems.findById(id).orElseThrow(() -> ApiException.missing("Problem not found."));
    }

    private List<Topic> requireTopics(List<Long> topicIds) {
        var ids = new HashSet<>(topicIds);
        if (ids.size() != topicIds.size()) {
            throw ApiException.invalid("topicIds", "Select each topic at most once.");
        }
        var matches = topics.findAllById(ids);
        if (matches.size() != ids.size()) {
            throw ApiException.invalid("topicIds", "One or more selected topics no longer exist.");
        }
        return matches;
    }

    private static ApiException duplicateId() {
        return ApiException.conflict("leetcodeId", "A problem with this LeetCode ID already exists.");
    }
}
