package dev.dsatracker.planning;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.ProblemRepository;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.planning.PlannerDtos.Calendar;
import dev.dsatracker.planning.PlannerDtos.Counts;
import dev.dsatracker.planning.PlannerDtos.Day;
import dev.dsatracker.planning.PlannerDtos.PlanInput;
import dev.dsatracker.planning.PlannerDtos.Task;
import dev.dsatracker.planning.PlannerDtos.TaskState;
import dev.dsatracker.planning.PlannerDtos.View;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlannerService {
    private final PlanItemRepository items;
    private final ProblemRepository problems;
    private final Clock clock;

    PlannerService(PlanItemRepository items, ProblemRepository problems, Clock clock) {
        this.items = items;
        this.problems = problems;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Calendar calendar(LocalDate requestedDate, View view) {
        LocalDate today = LocalDate.now(clock);
        LocalDate date = PlanningDates.checked("date", requestedDate == null ? today : requestedDate);
        LocalDate start = view == View.WEEK ? PlanningDates.monday(date) : date;
        LocalDate end = PlanningDates.checked("date", view == View.WEEK ? start.plusDays(6) : start);

        var tasks = items.findByDateBetweenOrderByDateAscIdAsc(start, end).stream().map(PlannerService::viewOf).toList();
        var byDate = tasks.stream().collect(Collectors.groupingBy(Task::date));
        var days = start.datesUntil(end.plusDays(1)).map(day -> {
            var scheduled = byDate.getOrDefault(day, List.of());
            return new Day(day, scheduled, Counts.from(scheduled));
        }).toList();
        return new Calendar(view, date, today, start, end, days, Counts.from(tasks));
    }

    @Transactional(readOnly = true)
    Task get(long id) {
        return viewOf(requireItem(id));
    }

    @Transactional
    Task schedule(PlanInput input) {
        LocalDate date = PlanningDates.checked("date", input.date());
        var problem = problems.findById(input.problemId()).orElseThrow(() -> ApiException.missing("Problem not found."));
        requireAvailableDate(input.problemId(), date);
        return viewOf(items.save(new PlanItem(problem, date)));
    }

    @Transactional
    Task reschedule(long id, LocalDate newDate) {
        var item = requireItem(id);
        LocalDate date = PlanningDates.checked("date", newDate);
        if (!item.date.equals(date)) {
            move(item, date, false);
        }
        return viewOf(item);
    }

    @Transactional
    Task carryForward(long id, LocalDate newDate) {
        var item = requireItem(id);
        LocalDate date = PlanningDates.checked("date", newDate);
        if (!date.isAfter(item.date)) {
            throw ApiException.invalid("date", "Choose a later date to carry this task forward.");
        }
        move(item, date, true);
        return viewOf(item);
    }

    @Transactional
    Task setSkipped(long id, boolean skipped) {
        var item = requireItem(id);
        if (skipped) {
            requireIncomplete(viewOf(item));
        }
        item.skipped = skipped;
        return viewOf(item);
    }

    @Transactional
    void remove(long id) {
        items.delete(requireItem(id));
    }

    private void move(PlanItem item, LocalDate date, boolean restore) {
        var task = viewOf(item);
        requireIncomplete(task);
        requireAvailableDate(task.problem().id(), date);
        item.date = date;
        if (restore) {
            item.skipped = false;
        }
    }

    private static Task viewOf(PlanItem item) {
        var problem = item.problem.reference();
        var state = TaskState.PLANNED;
        if (item.skipped) {
            state = TaskState.SKIPPED;
        } else if (problem.status() == ProgressStatus.SOLVED && item.date.equals(problem.solvedDate())) {
            state = TaskState.COMPLETED;
        }
        return new Task(item.id, item.date, problem, state);
    }

    private static void requireIncomplete(Task task) {
        if (task.state() == TaskState.COMPLETED) {
            throw ApiException.conflict("id", "Completed tasks cannot be moved or skipped.");
        }
    }

    private void requireAvailableDate(long problemId, LocalDate date) {
        if (items.existsByProblemIdAndDate(problemId, date)) {
            throw ApiException.conflict("date", "This problem is already scheduled on that date.");
        }
    }

    private PlanItem requireItem(long id) {
        PlanningDates.requirePositiveId(id);
        return items.findById(id).orElseThrow(() -> ApiException.missing("Plan item not found."));
    }
}
