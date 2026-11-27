package dev.dsatracker.planning;

import dev.dsatracker.planning.GoalDtos.GoalInput;
import dev.dsatracker.planning.GoalDtos.GoalProgress;
import dev.dsatracker.planning.GoalDtos.GoalView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/goals", "/api/v1/goals"})
class GoalController {
    private final GoalService service;

    GoalController(GoalService service) {
        this.service = service;
    }

    @GetMapping
    List<GoalView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    GoalView get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/progress")
    GoalProgress progress(@PathVariable long id) {
        return service.get(id).progress();
    }

    @GetMapping("/{id}/problems")
    GoalDtos.GoalProblemPage problems(@PathVariable long id,
            @RequestParam(defaultValue = "ALL") GoalDtos.ProblemFilter filter,
            @RequestParam(defaultValue = "LEETCODE_ID") GoalDtos.ProblemSort sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.problems(id, filter, sort, page, size);
    }

    @PostMapping
    ResponseEntity<GoalView> create(@Valid @RequestBody GoalInput input) {
        var goal = service.create(input);
        return ResponseEntity.created(URI.create("/api/goals/" + goal.id())).body(goal);
    }

    @PutMapping("/{id}")
    GoalView update(@PathVariable long id, @Valid @RequestBody GoalInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        service.delete(id);
    }
}
