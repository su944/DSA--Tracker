package dev.dsatracker.catalog;

import dev.dsatracker.catalog.CatalogDtos.TopicInput;
import dev.dsatracker.catalog.CatalogDtos.TopicView;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/topics", "/api/v1/topics"})
class TopicController {
    private final TopicService service;

    TopicController(TopicService service) {
        this.service = service;
    }

    @GetMapping
    List<TopicView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    TopicView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<TopicView> create(@Valid @RequestBody TopicInput input) {
        var topic = service.create(input.name());
        return ResponseEntity.created(URI.create("/api/topics/" + topic.id())).body(topic);
    }

    @PutMapping("/{id}")
    TopicView update(@PathVariable long id, @Valid @RequestBody TopicInput input) {
        return service.update(id, input.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        service.delete(id);
    }
}
