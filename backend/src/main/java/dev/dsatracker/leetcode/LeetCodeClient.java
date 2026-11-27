package dev.dsatracker.leetcode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
@EnableConfigurationProperties(LeetCodeSettings.class)
class LeetCodeClient {
    private static final String QUERY = """
            query problemsetQuestionList(
              $categorySlug: String
              $limit: Int
              $skip: Int
              $filters: QuestionListFilterInput
            ) {
              problemsetQuestionList: questionList(
                categorySlug: $categorySlug
                limit: $limit
                skip: $skip
                filters: $filters
              ) {
                total: totalNum
                questions: data {
                  frontendQuestionId: questionFrontendId
                  title
                  titleSlug
                  difficulty
                  isPaidOnly
                  topicTags { name slug }
                }
              }
            }
            """;

    private final RestClient rest;
    private final LeetCodeSettings settings;
    private final Semaphore requestSlot = new Semaphore(1);
    private Instant retryAfter = Instant.EPOCH;

    LeetCodeClient(LeetCodeSettings settings) {
        this.settings = settings;
        var http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(settings.readTimeout());
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    Optional<Page> fetch(int limit, int skip, long deadline) {
        if (!requestSlot.tryAcquire()) {
            throw remoteError("Another LeetCode request is running. Try again shortly.");
        }
        try {
            if (Instant.now().isBefore(retryAfter)) {
                throw remoteError("LeetCode is rate limiting discovery. Try again later.");
            }
            var body = Map.of("operationName", "problemsetQuestionList", "query", QUERY, "variables",
                    Map.of("categorySlug", "", "limit", limit, "skip", skip, "filters", Map.of()));
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!hasTime(deadline, settings.readTimeout())) {
                    return Optional.empty();
                }
                try {
                    var response = rest.post().uri(settings.endpoint()).contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
                    return Optional.of(parsePage(response, limit));
                } catch (HttpStatusCodeException exception) {
                    int status = exception.getStatusCode().value();
                    if (status == 429) {
                        var headers = exception.getResponseHeaders();
                        var delay = rateLimitDelay(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER));
                        retryAfter = Instant.now().plus(delay);
                        if (attempt == 0 && delay.compareTo(Duration.ofMillis(500)) <= 0
                                && hasTime(deadline, delay.plus(settings.readTimeout()))) {
                            pause(delay);
                            continue;
                        }
                        throw remoteError("LeetCode is rate limiting discovery. Try again later.");
                    }
                    if (status < 500 || attempt != 0 || !hasTime(deadline, Duration.ofMillis(150).plus(settings.readTimeout()))) {
                        throw remoteError("LeetCode returned HTTP " + status + ".");
                    }
                } catch (ResourceAccessException exception) {
                    if (attempt != 0 || !hasTime(deadline, Duration.ofMillis(150).plus(settings.readTimeout()))) {
                        throw remoteError("LeetCode could not be reached or timed out.");
                    }
                } catch (RestClientException exception) {
                    throw remoteError("LeetCode returned an unreadable response.");
                }
                pause(Duration.ofMillis(150));
            }
            throw remoteError("LeetCode could not complete the request.");
        } finally {
            requestSlot.release();
        }
    }

    private static Page parsePage(JsonNode response, int limit) {
        if (response == null || !response.isObject()) {
            throw remoteError("LeetCode returned an unreadable response.");
        }
        var errors = response.path("errors");
        if (!errors.isMissingNode() && (!errors.isArray() || !errors.isEmpty())) {
            throw remoteError("LeetCode rejected the GraphQL query. The unofficial API may have changed.");
        }
        var data = response.path("data").path("problemsetQuestionList");
        var total = data.path("total");
        var questions = data.path("questions");
        if (!total.isIntegralNumber() || !total.canConvertToLong() || total.asLong() < 0
                || !questions.isArray() || questions.size() > limit) {
            throw remoteError("LeetCode returned an unexpected GraphQL catalog shape.");
        }
        var rows = new ArrayList<JsonNode>(questions.size());
        questions.forEach(rows::add);
        return new Page(total.asLong(), rows);
    }

    private static boolean hasTime(long deadline, Duration required) {
        return deadline - System.nanoTime() >= required.toNanos();
    }

    private static Duration rateLimitDelay(String header) {
        if (header != null) {
            try {
                return Duration.ofSeconds(Math.max(1, Math.min(86_400, Long.parseLong(header.trim()))));
            } catch (NumberFormatException ignored) {
                try {
                    var delay = Duration.between(Instant.now(),
                            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                    if (!delay.isNegative()) {
                        return delay.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : delay;
                    }
                } catch (DateTimeParseException invalidDate) {
                    // Use a conservative cooldown when no valid Retry-After is supplied.
                }
            }
        }
        return Duration.ofSeconds(30);
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw remoteError("LeetCode discovery was interrupted.");
        }
    }

    private static IllegalStateException remoteError(String message) {
        return new IllegalStateException(message);
    }

    record Page(long total, List<JsonNode> questions) {}
}
