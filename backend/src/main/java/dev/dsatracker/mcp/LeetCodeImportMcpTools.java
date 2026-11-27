package dev.dsatracker.mcp;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.leetcode.LeetCodeDtos.ImportResult;
import dev.dsatracker.leetcode.LeetCodeImportService;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"spring.ai.mcp.server.enabled", "tracker.mcp-writes-enabled"}, havingValue = "true")
public class LeetCodeImportMcpTools {
    private final LeetCodeImportService importer;

    public LeetCodeImportMcpTools(LeetCodeImportService importer) {
        this.importer = importer;
    }

    @McpTool(name = "import_leetcode_problems",
            description = "Import selected problems from a stored LeetCode preview. Obtain user approval first.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ImportResult importProblems(
            @McpToolParam(description = "UUID from preview_leetcode_import.") String previewId,
            @McpToolParam(description = "One to 500 positive LeetCode IDs from the preview.") List<Long> leetcodeIds) {
        UUID id;
        try {
            id = UUID.fromString(previewId);
            if (!id.toString().equalsIgnoreCase(previewId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid("previewId", "Provide a valid preview UUID.");
        }
        return importer.importSelection(id, leetcodeIds);
    }
}
