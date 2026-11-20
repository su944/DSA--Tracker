package dev.dsatracker.catalog;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.CatalogDtos.TopicView;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TopicService {
    private final TopicRepository topics;
    private final ProblemRepository problems;
    private final Clock clock;

    TopicService(TopicRepository topics, ProblemRepository problems, Clock clock) {
        this.topics = topics;
        this.problems = problems;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<TopicView> list() {
        return topics.findAllByOrderByNameKeyAsc().stream().map(TopicView::from).toList();
    }

    @Transactional(readOnly = true)
    TopicView get(long id) {
        return TopicView.from(requireTopic(id));
    }

    @Transactional
    TopicView create(String name) {
        var topic = new Topic(name, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        if (topics.existsByNameKey(topic.nameKey)) {
            throw duplicateName();
        }
        return TopicView.from(topics.save(topic));
    }

    @Transactional
    TopicView update(long id, String name) {
        var topic = requireTopic(id);
        if (topics.existsByNameKeyAndIdNot(Topic.comparisonKey(name), id)) {
            throw duplicateName();
        }
        topic.rename(name);
        return TopicView.from(topic);
    }

    @Transactional
    void delete(long id) {
        var topic = requireTopic(id);
        if (problems.countByTopic(id) > 0) {
            throw ApiException.conflict("id", "Remove this topic from its problems before deleting it.");
        }
        if (topics.countGoalsByTopic(id) > 0) {
            throw ApiException.conflict("id", "Remove this topic from its goals before deleting it.");
        }
        topics.delete(topic);
    }

    private Topic requireTopic(long id) {
        if (id <= 0) {
            throw ApiException.invalid("id", "Topic ID must be positive.");
        }
        return topics.findById(id).orElseThrow(() -> ApiException.missing("Topic not found."));
    }

    private static ApiException duplicateName() {
        return ApiException.conflict("name", "A topic with this name already exists.");
    }
}
