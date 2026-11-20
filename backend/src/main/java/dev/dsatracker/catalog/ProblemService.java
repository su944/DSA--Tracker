package dev.dsatracker.catalog;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.CatalogDtos.ProblemInput;
import dev.dsatracker.catalog.CatalogDtos.ProblemPage;
import dev.dsatracker.catalog.CatalogDtos.ProblemView;
import dev.dsatracker.catalog.CatalogDtos.ProgressPatch;
import dev.dsatracker.catalog.CatalogDtos.ProgressView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProblemService {
    private final ProblemRepository problems;
    private final TopicRepository topics;
    private final Clock clock;

    ProblemService(ProblemRepository problems, TopicRepository topics, Clock clock) {
        this.problems = problems;
        this.topics = topics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    ProblemPage list(ProblemQuery query) {
        var page = problems.findAll(query.specification(), query.pageRequest());
        return new ProblemPage(page.map(ProblemView::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    ProblemView get(long id) {
        return ProblemView.from(requireProblem(id));
    }

    @Transactional
    ProblemView create(ProblemInput input) {
        if (problems.existsByLeetcodeId(input.leetcodeId())) {
            throw duplicateId();
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var problem = new Problem(now);
        problem.edit(input, LeetCodeLink.parse(input.url()), requireTopics(input.topicIds()), now);
        return ProblemView.from(problems.save(problem));
    }

    @Transactional
    ProblemView update(long id, ProblemInput input) {
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
    ProgressView updateProgress(long id, ProgressPatch patch) {
        var progress = requireProblem(id).progress;
        var status = patch.status() == null ? progress.status : patch.status();
        LocalDate today = LocalDate.now(clock);
        LocalDate attempted = patch.attemptedDateProvided() ? patch.attemptedDate() : progress.attemptedDate;
        LocalDate solved = patch.solvedDateProvided() ? patch.solvedDate() : progress.solvedDate;
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

        String comments = patch.comments() == null ? progress.comments : patch.comments();
        progress.edit(status, attempted, solved, comments, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return ProgressView.from(progress);
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
