package dev.dsatracker.mcp;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.leetcode.LeetCodeCatalogService;
import dev.dsatracker.leetcode.LeetCodeDtos.Preview;
import dev.dsatracker.leetcode.LeetCodeDtos.ProblemItem;
import dev.dsatracker.leetcode.LeetCodeDtos.SearchResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class LeetCodeMcpTools {
    private final LeetCodeCatalogService catalog;

    public LeetCodeMcpTools(LeetCodeCatalogService catalog) {
        this.catalog = catalog;
    }

    @McpTool(name = "search_leetcode_problems", description = "Search public LeetCode metadata and check local catalog membership.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public SearchResult search(
            @McpToolParam(required = false, description = "Case-insensitive title substring.") String query,
            @McpToolParam(required = false, description = "Case-insensitive topic names; match any.") List<String> topicNames,
            @McpToolParam(required = false, description = "Filter by difficulty.") Difficulty difficulty,
            @McpToolParam(required = false, description = "Include paid problems. Default false.") Boolean includePremium,
            @McpToolParam(required = false, description = "Page size, 1 to 100. Default 20.") Integer limit,
            @McpToolParam(required = false, description = "Zero-based page. Default 0.") Integer page) {
        return catalog.search(query, topicNames, difficulty, Boolean.TRUE.equals(includePremium),
                limit == null ? 20 : limit, page == null ? 0 : page);
    }

    @McpTool(name = "get_leetcode_problem", description = "Look up public LeetCode metadata by numeric problem ID.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public ProblemItem get(
            @McpToolParam(description = "Positive numeric LeetCode problem ID.") long leetcodeId) {
        return catalog.get(leetcodeId);
    }

    @McpTool(name = "preview_leetcode_import", description = "Preview public LeetCode problems missing from the local catalog.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true))
    public Preview preview(
            @McpToolParam(required = false, description = "Number of candidates, 1 to 500. Default 100.") Integer count,
            @McpToolParam(required = false, description = "Case-insensitive topic names; match any.") List<String> topicNames,
            @McpToolParam(required = false, description = "Filter by difficulty.") Difficulty difficulty,
            @McpToolParam(required = false, description = "Include paid problems. Default false.") Boolean includePremium) {
        return catalog.preview(count == null ? 100 : count, topicNames, difficulty, Boolean.TRUE.equals(includePremium));
    }
}
