package dev.dsatracker.revision;

import dev.dsatracker.revision.RevisionDtos.Filter;
import dev.dsatracker.revision.RevisionDtos.Queue;
import dev.dsatracker.revision.RevisionDtos.Reschedule;
import dev.dsatracker.revision.RevisionDtos.Schedule;
import dev.dsatracker.revision.RevisionDtos.Status;
import dev.dsatracker.revision.RevisionDtos.Task;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/revisions", "/api/v1/revisions"})
class RevisionController {
    private final RevisionService revisions;

    RevisionController(RevisionService revisions) {
        this.revisions = revisions;
    }

    @GetMapping
    Queue list(@RequestParam(defaultValue = "DUE_TODAY") Filter filter) {
        return revisions.queue(filter);
    }

    @GetMapping("/{id}")
    Task get(@PathVariable long id) {
        return revisions.get(id);
    }

    @PostMapping
    ResponseEntity<Task> create(@Valid @RequestBody Schedule input) {
        var task = revisions.schedule(input.problemId(), input.date());
        return ResponseEntity.created(URI.create("/api/revisions/" + task.id())).body(task);
    }

    @PutMapping("/{id}")
    Task move(@PathVariable long id, @Valid @RequestBody Reschedule input) {
        return revisions.move(id, input.date());
    }

    @PostMapping("/{id}/complete")
    Task complete(@PathVariable long id) {
        return revisions.finish(id, Status.COMPLETED);
    }

    @PostMapping("/{id}/cancel")
    Task cancel(@PathVariable long id) {
        return revisions.finish(id, Status.CANCELLED);
    }
}
