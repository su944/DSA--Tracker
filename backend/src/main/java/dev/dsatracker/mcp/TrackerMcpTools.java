package dev.dsatracker.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class TrackerMcpTools {

    @McpTool(name = "ping_tracker",
            description = "Checks that the DSA Tracker MCP server is running.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public PingResponse pingTracker() {
        return new PingResponse("UP", "DSA Tracker", "MCP server connected successfully");
    }

    public record PingResponse(String status, String application, String message) {}
}
