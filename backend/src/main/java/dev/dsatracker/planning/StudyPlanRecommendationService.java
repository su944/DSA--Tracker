package dev.dsatracker.planning;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.cache.ExpiringCache;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.catalog.TopicService;
import dev.dsatracker.planning.StudyPlanRecommendationRepository.Candidate;
import dev.dsatracker.planning.StudyPlanRecommendationRepository.ExistingDate;
import dev.dsatracker.planning.StudyPlanRecommendationRepository.Filter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyPlanRecommendationService {
    private static final Duration PROPOSAL_TTL = Duration.ofMinutes(10);

    private final StudyPlanRecommendationRepository repository;
    private final TopicService topics;
    private final Clock clock;
    private final ExpiringCache<UUID, StoredProposal> proposals;

    StudyPlanRecommendationService(StudyPlanRecommendationRepository repository, TopicService topics, Clock clock) {
        this.repository = repository;
        this.topics = topics;
        this.clock = clock;
        this.proposals = new ExpiringCache<>(32, clock);
    }

    @Transactional(readOnly = true)
    public Proposal recommend(List<String> topicNames, Difficulty difficulty, LocalDate startDate, int days,
                              int problemsPerDay, boolean excludeSolved, boolean excludeScheduled, int candidateLimit) {
        requireRange("days", days, 30);
        requireRange("problemsPerDay", problemsPerDay, 5);
        requireRange("candidateLimit", candidateLimit, 30);
        var start = PlanningDates.checked("startDate", startDate == null ? LocalDate.now(clock) : startDate);
        var end = PlanningDates.checked("startDate", start.plusDays(days - 1));
        var topicIds = topics.findIdsByNames(topicNames);
        int requested = days * problemsPerDay;
        long eligible = 0;
        List<Assignment> assignments = List.of();
        if (topicNames == null || topicNames.isEmpty() || !topicIds.isEmpty()) {
            var filter = new Filter(topicIds, difficulty, excludeSolved, excludeScheduled, start, end, days);
            eligible = repository.count(filter);
            var candidates = repository.candidates(filter, candidateLimit);
            var conflicts = excludeScheduled ? Set.<ExistingDate>of() : new HashSet<>(repository.existingDates(candidates, filter));
            assignments = assign(start, requested, problemsPerDay, candidates, conflicts);
        }
        UUID id = UUID.randomUUID();
        Instant expiry = clock.instant().plus(PROPOSAL_TTL);
        var schedule = assignments.stream().map(Assignment::entry).toList();
        var proposal = new Proposal(id, start, requested, eligible, schedule.size(), schedule,
                schedule.size() < requested, expiry);
        proposals.put(id, new StoredProposal(proposal, assignments, end, problemsPerDay, List.copyOf(topicIds),
                difficulty, excludeSolved, excludeScheduled), expiry);
        return proposal;
    }

    Optional<StoredProposal> findProposal(UUID proposalId) {
        return proposals.get(proposalId);
    }

    private static List<Assignment> assign(LocalDate start, int slots, int perDay,
                                           List<Candidate> candidates, Set<ExistingDate> conflicts) {
        var assigned = new Candidate[slots];
        for (var candidate : candidates) {
            place(candidate, start, perDay, assigned, conflicts, new boolean[slots]);
        }

        var result = new ArrayList<Assignment>();
        for (int i = 0; i < slots; i++) {
            var candidate = assigned[i];
            if (candidate != null) {
                var entry = new Entry(start.plusDays(i / perDay), candidate.leetcodeId(), candidate.title(),
                        candidate.difficulty(), candidate.status());
                result.add(new Assignment(candidate.problemId(), entry));
            }
        }
        return List.copyOf(result);
    }

    private static boolean place(Candidate candidate, LocalDate start, int perDay, Candidate[] assigned,
                                 Set<ExistingDate> conflicts, boolean[] visited) {
        for (int i = 0; i < assigned.length; i++) {
            if (assigned[i] == null && !visited[i]
                    && !conflicts.contains(new ExistingDate(candidate.problemId(), start.plusDays(i / perDay)))) {
                assigned[i] = candidate;
                return true;
            }
        }
        // Move an earlier choice only when all compatible slots are occupied.
        for (int i = 0; i < assigned.length; i++) {
            if (visited[i] || conflicts.contains(new ExistingDate(candidate.problemId(), start.plusDays(i / perDay)))) {
                continue;
            }
            visited[i] = true;
            if (place(assigned[i], start, perDay, assigned, conflicts, visited)) {
                assigned[i] = candidate;
                return true;
            }
        }
        return false;
    }

    private static void requireRange(String field, int value, int max) {
        if (value < 1 || value > max) {
            throw ApiException.invalid(field, field + " must be between 1 and " + max + ".");
        }
    }

    public record Entry(LocalDate date, long leetcodeId, String title, Difficulty difficulty, ProgressStatus status) {}

    public record Proposal(UUID proposalId, LocalDate startDate, int requestedCount, long eligibleCount,
                           int selectedCount, List<Entry> schedule, boolean insufficientCandidates, Instant expiresAt) {}

    record Assignment(long problemId, Entry entry) {}

    record StoredProposal(Proposal proposal, List<Assignment> assignments, LocalDate endDate, int problemsPerDay,
                          List<Long> topicIds, Difficulty difficulty, boolean excludeSolved, boolean excludeScheduled) {
        StoredProposal {
            assignments = List.copyOf(assignments);
            topicIds = List.copyOf(topicIds);
        }
    }
}
