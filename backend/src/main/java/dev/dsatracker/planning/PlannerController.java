package dev.dsatracker.planning;

import dev.dsatracker.planning.PlannerDtos.Calendar;
import dev.dsatracker.planning.PlannerDtos.DateInput;
import dev.dsatracker.planning.PlannerDtos.PlanInput;
import dev.dsatracker.planning.PlannerDtos.SkipInput;
import dev.dsatracker.planning.PlannerDtos.Task;
import dev.dsatracker.planning.PlannerDtos.View;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/planner", "/api/v1/planner"})
class PlannerController {
    private final PlannerService service;

    PlannerController(PlannerService service) {
        this.service = service;
    }

    @GetMapping
    Calendar calendar(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                      @RequestParam(defaultValue = "DAY") View view) {
        return service.calendar(date, view);
    }

    @GetMapping("/{id}")
    Task get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<Task> schedule(@Valid @RequestBody PlanInput input) {
        var item = service.schedule(input);
        return ResponseEntity.created(URI.create("/api/planner/" + item.id())).body(item);
    }

    @PutMapping("/{id}")
    Task reschedule(@PathVariable long id, @Valid @RequestBody DateInput input) {
        return service.reschedule(id, input.date());
    }

    @PatchMapping("/{id}/skip")
    Task skip(@PathVariable long id, @Valid @RequestBody SkipInput input) {
        return service.setSkipped(id, input.skipped());
    }

    @PostMapping("/{id}/carry-forward")
    Task carryForward(@PathVariable long id, @Valid @RequestBody DateInput input) {
        return service.carryForward(id, input.date());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable long id) {
        service.remove(id);
    }
}
