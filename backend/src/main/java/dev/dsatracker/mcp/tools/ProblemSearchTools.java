package dev.dsatracker.mcp.tools;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.CatalogDtos.ProblemView;
import dev.dsatracker.catalog.CatalogDtos.TopicView;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.catalog.TopicService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class ProblemSearchTools {
    private final ProblemService problems;
    private final TopicService topics;

    public ProblemSearchTools(ProblemService problems, TopicService topics) {
        this.problems = problems;
        this.topics = topics;
    }

    @McpTool(name = "search_problems",
            description = "Search problems stored in the DSA Tracker by LeetCode ID, title, topics, difficulty and status.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchResult searchProblems(
            @McpToolParam(required = false, description = "Title substring or LeetCode ID.") String query,
            @McpToolParam(required = false, description = "Exact positive LeetCode ID.") Long leetcodeId,
            @McpToolParam(required = false, description = "Exact topic names, case-insensitive. Matches any listed topic.")
            List<String> topicNames,
            @McpToolParam(required = false, description = "Problem difficulty.") Difficulty difficulty,
            @McpToolParam(required = false, description = "Current progress status.") ProgressStatus status,
            @McpToolParam(required = false, description = "Zero-based page index. Default: 0.") Integer page,
            @McpToolParam(required = false, description = "Page size from 1 to 100. Default: 20.") Integer size) {
        var matches = problems.searchCatalog(query, leetcodeId, topicNames, difficulty, status,
                page == null ? 0 : page, size == null ? 20 : size);
        return new SearchResult(matches.items().stream().map(ProblemSummary::from).toList(),
                matches.page(), matches.size(), matches.totalItems(), matches.totalPages());
    }

    @McpTool(name = "get_problem",
            description = "Retrieve full information for one problem from the local DSA Tracker.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ProblemDetails getProblem(
            @McpToolParam(description = "Exact positive LeetCode ID.") Long leetcodeId) {
        if (leetcodeId == null) {
            throw ApiException.invalid("leetcodeId", "Provide a positive LeetCode ID.");
        }
        var problem = problems.getByLeetcodeId(leetcodeId);
        var progress = problem.progress();
        return new ProblemDetails(problem.id(), problem.leetcodeId(), problem.title(), problem.slug(), problem.url(),
                problem.difficulty(), topicNames(problem),
                new Progress(progress.status(), progress.attemptedDate(), progress.solvedDate(), progress.comments()));
    }

    @McpTool(name = "list_topics",
            description = "List the topics available in the DSA Tracker.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TopicList listTopics(
            @McpToolParam(required = false, description = "Case-insensitive topic name substring.") String query) {
        return new TopicList(topics.search(query).stream().map(topic -> new TopicItem(topic.id(), topic.name())).toList());
    }

    private static List<String> topicNames(ProblemView problem) {
        return problem.topics().stream().map(TopicView::name).toList();
    }

    public record SearchResult(List<ProblemSummary> items, int page, int size, long totalItems, int totalPages) {}

    public record ProblemSummary(long id, long leetcodeId, String title, String url, Difficulty difficulty,
                                 List<String> topics, ProgressStatus status,
                                 @Schema(nullable = true) LocalDate attemptedDate,
                                 @Schema(nullable = true) LocalDate solvedDate) {
        static ProblemSummary from(ProblemView problem) {
            var progress = problem.progress();
            return new ProblemSummary(problem.id(), problem.leetcodeId(), problem.title(), problem.url(),
                    problem.difficulty(), topicNames(problem), progress.status(), progress.attemptedDate(), progress.solvedDate());
        }
    }

    public record ProblemDetails(long id, long leetcodeId, String title, String slug, String url, Difficulty difficulty,
                                 List<String> topics, Progress progress) {}

    public record Progress(ProgressStatus status, @Schema(nullable = true) LocalDate attemptedDate,
                           @Schema(nullable = true) LocalDate solvedDate, String comments) {}

    public record TopicList(List<TopicItem> topics) {}

    public record TopicItem(long id, String name) {}
}
