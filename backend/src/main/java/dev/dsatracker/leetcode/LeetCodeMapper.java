package dev.dsatracker.leetcode;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.LeetCodeLink;
import dev.dsatracker.catalog.Topic;
import dev.dsatracker.leetcode.LeetCodeDtos.Metadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

final class LeetCodeMapper {
    private LeetCodeMapper() {}

    static Optional<Metadata> map(JsonNode question) {
        var rawId = question.path("frontendQuestionId");
        String title = question.path("title").asString("").strip();
        String slug = question.path("titleSlug").asString("");
        var paid = question.path("isPaidOnly");
        var tags = question.path("topicTags");
        if (!rawId.isString() || !rawId.asString().matches("[0-9]{1,19}") || !question.path("title").isString()
                || title.isBlank() || title.length() > 255 || !question.path("titleSlug").isString()
                || !question.path("difficulty").isString() || !paid.isBoolean() || !tags.isArray()) {
            return Optional.empty();
        }
        try {
            long id = Long.parseLong(rawId.asString());
            if (id < 1) return Optional.empty();
            var difficulty = Difficulty.valueOf(question.path("difficulty").asString("").toUpperCase(Locale.ROOT));
            var link = LeetCodeLink.parse("https://leetcode.com/problems/" + slug + "/");
            if (!link.slug().equals(slug)) return Optional.empty();
            var topics = new LinkedHashMap<String, String>();
            for (var tag : tags) {
                var name = tag.path("name");
                if (!name.isString() || name.asString().isBlank() || name.asString().strip().length() > 80) {
                    return Optional.empty();
                }
                topics.putIfAbsent(Topic.comparisonKey(name.asString()), name.asString().strip());
            }
            return Optional.of(new Metadata(id, title, slug, link.url(), difficulty,
                    List.copyOf(topics.values()), paid.asBoolean()));
        } catch (IllegalArgumentException | ApiException invalid) {
            return Optional.empty();
        }
    }
}
