package dev.dsatracker.planning;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.TopicRepository;
import dev.dsatracker.planning.GoalDtos.GoalInput;
import dev.dsatracker.planning.GoalDtos.GoalView;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GoalService {
    private final GoalRepository goals;
    private final TopicRepository topics;

    GoalService(GoalRepository goals, TopicRepository topics) {
        this.goals = goals;
        this.topics = topics;
    }

    @Transactional(readOnly = true)
    List<GoalView> list() {
        return goals.listWithProgress().stream().map(GoalView::from).toList();
    }

    @Transactional(readOnly = true)
    GoalView get(long id) {
        PlanningDates.requirePositiveId(id);
        return GoalView.from(goals.findWithProgress(id).orElseThrow(() -> ApiException.missing("Goal not found.")));
    }

    @Transactional
    GoalView create(GoalInput input) {
        var goal = new Goal();
        apply(goal, input);
        return get(goals.saveAndFlush(goal).id);
    }

    @Transactional
    GoalView update(long id, GoalInput input) {
        apply(requireGoal(id), input);
        goals.flush();
        return get(id);
    }

    @Transactional
    void delete(long id) {
        goals.delete(requireGoal(id));
    }

    private void apply(Goal goal, GoalInput input) {
        var start = PlanningDates.checked("startDate", input.startDate());
        var end = switch (input.type()) {
            case DAILY -> start;
            case WEEKLY -> {
                if (!start.equals(PlanningDates.monday(start))) {
                    throw ApiException.invalid("startDate", "A weekly goal starts on Monday.");
                }
                yield PlanningDates.checked("endDate", start.plusDays(6));
            }
            case CUSTOM -> {
                if (input.endDate() == null) {
                    throw ApiException.invalid("endDate", "Choose an end date for the custom range.");
                }
                yield PlanningDates.checked("endDate", input.endDate());
            }
        };
        validateEnd(input, start, end);
        var topic = input.topicId() == null ? null : topics.findById(input.topicId())
                .orElseThrow(() -> ApiException.invalid("topicId", "Choose an existing topic."));
        goal.edit(input, end, topic);
    }

    private static void validateEnd(GoalInput input, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw ApiException.invalid("endDate", "End date must be on or after the start date.");
        }
        if (input.endDate() != null && !input.endDate().equals(end)) {
            throw ApiException.invalid("endDate", "The end date must match the selected daily or weekly period.");
        }
    }

    private Goal requireGoal(long id) {
        PlanningDates.requirePositiveId(id);
        return goals.findById(id).orElseThrow(() -> ApiException.missing("Goal not found."));
    }
}
