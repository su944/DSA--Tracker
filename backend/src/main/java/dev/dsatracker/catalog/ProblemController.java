package dev.dsatracker.catalog;

import dev.dsatracker.catalog.CatalogDtos.ProblemInput;
import dev.dsatracker.catalog.CatalogDtos.ProblemPage;
import dev.dsatracker.catalog.CatalogDtos.ProblemView;
import dev.dsatracker.catalog.CatalogDtos.ProgressPatch;
import dev.dsatracker.catalog.CatalogDtos.ProgressView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
@RequestMapping({"/api/problems", "/api/v1/problems"})
class ProblemController {
    private final ProblemService service;

    ProblemController(ProblemService service) {
        this.service = service;
    }

    @GetMapping
    ProblemPage list(@RequestParam(defaultValue = "") String q,
                     @RequestParam(name = "topic", required = false) List<Long> topics,
                     @RequestParam(required = false) Difficulty difficulty,
                     @RequestParam(required = false) ProgressStatus status,
                     @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size,
                     @RequestParam(defaultValue = "leetcodeId,asc") String sort) {
        return service.list(new ProblemQuery(q, topics, difficulty, status, page, size, sort));
    }

    @GetMapping("/{id}")
    ProblemView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<ProblemView> create(@Valid @RequestBody ProblemInput input) {
        var problem = service.create(input);
        return ResponseEntity.created(URI.create("/api/problems/" + problem.id())).body(problem);
    }

    @PutMapping("/{id}")
    ProblemView update(@PathVariable long id, @Valid @RequestBody ProblemInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PatchMapping("/{id}/progress")
    ProgressView updateProgress(@PathVariable long id, @Valid @RequestBody ProgressPatch input) {
        return service.updateProgress(id, input);
    }
}
