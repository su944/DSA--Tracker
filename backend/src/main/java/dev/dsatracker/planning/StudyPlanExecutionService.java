package dev.dsatracker.planning;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.planning.PlannerDtos.PlanInput;
import dev.dsatracker.planning.StudyPlanExecutionRepository.Execution;
import dev.dsatracker.planning.StudyPlanRecommendationService.Proposal;
import dev.dsatracker.planning.StudyPlanRecommendationService.StoredProposal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StudyPlanExecutionService {
    private static final Logger log = LoggerFactory.getLogger(StudyPlanExecutionService.class);

    private final StudyPlanRecommendationService recommendations;
    private final StudyPlanExecutionRepository executions;
    private final ProblemService problems;
    private final PlannerService planner;
    private final TransactionTemplate transaction;
    private final Clock clock;

    StudyPlanExecutionService(StudyPlanRecommendationService recommendations, StudyPlanExecutionRepository executions,
                              ProblemService problems, PlannerService planner, PlatformTransactionManager transactions,
                              Clock clock) {
        this.recommendations = recommendations;
        this.executions = executions;
        this.problems = problems;
        this.planner = planner;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Transactional
    public ApprovalView requestApproval(UUID id) {
        var existing = executions.find(id);
        if (existing.isPresent()) return view(expire(id, existing.orElseThrow()));
        var snapshot = requireProposal(id);
        if (snapshot.assignments().isEmpty()) {
            throw ApiException.invalid("proposalId", "Cannot request approval for an empty plan.");
        }
        executions.request(id, snapshot);
        return new ApprovalView(State.PENDING_APPROVAL, snapshot.proposal());
    }

    @Transactional
    public ApprovalView decide(UUID id, boolean approved) {
        var current = executions.find(id).orElseThrow(() -> ApiException.missing("No approval request exists for this proposal."));
        current = expire(id, current);
        if (current.state() != State.PENDING_APPROVAL) return view(current);
        State next = approved ? State.APPROVED : State.REJECTED;
        executions.transition(id, current.state(), next, null);
        return new ApprovalView(next, current.snapshot().proposal());
    }

    public CommitResult commitApprovedPlan(UUID id) {
        try {
            return transaction.execute(status -> commit(id));
        } catch (InvalidPlan failure) {
            return recordFailure(id, failure.getMessage());
        } catch (DataAccessException | TransactionException failure) {
            log.warn("Study plan commit failed for {}", id, failure);
            return recordFailure(id, "Planner entries could not be saved. No partial batch was committed.");
        }
    }

    private CommitResult recordFailure(UUID id, String message) {
        return transaction.execute(status -> {
            var current = expire(id, executions.find(id).orElseThrow());
            if (current.state() != State.APPROVED) return deniedOrCommitted(id, current);
            var result = outcome(id, State.FAILED, message);
            executions.transition(id, State.APPROVED, State.FAILED, result);
            return result;
        });
    }

    private CommitResult commit(UUID id) {
        var found = executions.find(id);
        if (found.isEmpty()) {
            requireProposal(id);
            return outcome(id, State.PROPOSED, "Request approval for this proposal through the operator endpoint.");
        }
        var current = expire(id, found.orElseThrow());
        if (current.state() != State.APPROVED) return deniedOrCommitted(id, current);

        List<CreatedEntry> entries;
        try {
            validate(current.snapshot());
            entries = current.snapshot().assignments().stream().map(assignment -> {
                var task = planner.schedule(new PlanInput(assignment.problemId(), assignment.entry().date()));
                return new CreatedEntry(task.id(), task.problem().leetcodeId(), task.date());
            }).toList();
        } catch (ApiException failure) {
            throw new InvalidPlan(failure.getMessage());
        }
        var result = new CommitResult(id, State.COMMITTED, entries.size(), entries, null);
        executions.transition(id, State.APPROVED, State.COMMITTED, result);
        return result;
    }

    private void validate(StoredProposal snapshot) {
        var proposal = snapshot.proposal();
        var seen = new HashSet<Long>();
        var daily = new HashMap<LocalDate, Integer>();
        var start = PlanningDates.checked("startDate", proposal.startDate());
        var end = PlanningDates.checked("endDate", snapshot.endDate());
        if (snapshot.problemsPerDay() < 1 || snapshot.problemsPerDay() > 5 || end.isBefore(start)
                || end.isAfter(start.plusDays(29)) || snapshot.assignments().isEmpty()
                || snapshot.assignments().size() > 30
                || snapshot.assignments().size() != proposal.selectedCount()
                || snapshot.assignments().size() > proposal.requestedCount()
                || (ChronoUnit.DAYS.between(start, end) + 1) * snapshot.problemsPerDay() != proposal.requestedCount()) {
            throw new InvalidPlan("The approved proposal has an invalid date window or capacity.");
        }
        for (var assignment : snapshot.assignments()) {
            var entry = assignment.entry();
            var date = entry.date();
            if (!seen.add(assignment.problemId()) || date.isBefore(start) || date.isAfter(end)
                    || daily.merge(date, 1, Integer::sum) > snapshot.problemsPerDay()) {
                throw new InvalidPlan("The approved proposal repeats a problem or exceeds its date or capacity limits.");
            }

            var problem = problems.getByLeetcodeId(entry.leetcodeId());
            if (problem.id() != assignment.problemId() || problem.difficulty() != entry.difficulty()
                    || (snapshot.difficulty() != null && problem.difficulty() != snapshot.difficulty())
                    || (!snapshot.topicIds().isEmpty()
                        && problem.topics().stream().noneMatch(topic -> snapshot.topicIds().contains(topic.id())))) {
                throw new InvalidPlan("A proposed problem no longer meets the approved topic or difficulty constraints.");
            }
            if (problem.progress().status() != entry.status()
                    || (snapshot.excludeSolved() && problem.progress().status() == ProgressStatus.SOLVED)) {
                throw new InvalidPlan("A proposed problem's progress has changed. Generate a new proposal.");
            }
            planner.validateProposalDate(problem.id(), date, snapshot.excludeScheduled());
        }
    }

    private StoredProposal requireProposal(UUID id) {
        return recommendations.findProposal(id)
                .orElseThrow(() -> ApiException.missing("Study plan proposal was not found or has expired."));
    }

    private Execution expire(UUID id, Execution execution) {
        if (execution.state().allows(State.EXPIRED)
                && !execution.snapshot().proposal().expiresAt().isAfter(clock.instant())) {
            executions.transition(id, execution.state(), State.EXPIRED, null);
            return new Execution(State.EXPIRED, execution.snapshot(), null);
        }
        return execution;
    }

    private static CommitResult deniedOrCommitted(UUID id, Execution execution) {
        if (execution.result() != null) return execution.result();
        String error = switch (execution.state()) {
            case PENDING_APPROVAL -> "Human approval is pending.";
            case REJECTED -> "The proposal was rejected by the operator.";
            case EXPIRED -> "The proposal has expired.";
            default -> throw new IllegalStateException("Unexpected study plan state: " + execution.state());
        };
        return outcome(id, execution.state(), error);
    }

    private static ApprovalView view(Execution execution) {
        return new ApprovalView(execution.state(), execution.snapshot().proposal());
    }

    private static CommitResult outcome(UUID id, State state, String error) {
        return new CommitResult(id, state, 0, List.of(), error);
    }

    public enum State {
        PROPOSED, PENDING_APPROVAL, APPROVED, COMMITTED, REJECTED, EXPIRED, FAILED;

        boolean allows(State next) {
            return switch (this) {
                case PROPOSED -> next == PENDING_APPROVAL;
                case PENDING_APPROVAL -> next == APPROVED || next == REJECTED || next == EXPIRED;
                case APPROVED -> next == COMMITTED || next == FAILED || next == EXPIRED;
                default -> false;
            };
        }
    }

    public record ApprovalView(State status, Proposal proposal) {}

    public record CreatedEntry(long plannerEntryId, long leetcodeId, LocalDate date) {}

    public record CommitResult(UUID proposalId, State status, int createdCount, List<CreatedEntry> plannerEntries,
                               @Schema(nullable = true) String error) {}

    private static class InvalidPlan extends RuntimeException {
        InvalidPlan(String message) { super(message); }
    }
}
