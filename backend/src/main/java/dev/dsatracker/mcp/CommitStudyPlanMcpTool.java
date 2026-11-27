package dev.dsatracker.mcp;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.planning.StudyPlanApprovalAccess;
import dev.dsatracker.planning.StudyPlanExecutionService;
import dev.dsatracker.planning.StudyPlanExecutionService.CommitResult;
import dev.dsatracker.planning.StudyPlanExecutionService.State;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.ai.mcp.server.enabled", "tracker.mcp-writes-enabled"}, havingValue = "true")
class CommitStudyPlanMcpTool {
    @Bean
    List<SyncToolSpecification> studyPlanCommitTools(StudyPlanExecutionService execution, StudyPlanApprovalAccess access,
                                                     @Qualifier("mcpServerJsonMapper") JsonMapper json) {
        if (!access.configured()) return List.of();
        var tool = Tool.builder().name("commit_study_plan")
                .description("Atomically schedule the exact saved proposal after the local operator approves it.")
                .inputSchema(Map.of("type", "object", "required", List.of("proposalId"), "additionalProperties", false,
                        "properties", Map.of("proposalId", Map.of("type", "string", "format", "uuid"))))
                .outputSchema(json.readValue(McpJsonSchemaGenerator.generateFromClass(CommitResult.class),
                        new TypeReference<Map<String, Object>>() {}))
                .annotations(ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .build();
        return List.of(new SyncToolSpecification(tool, (exchange, request) -> {
            UUID id;
            try {
                var text = (String) request.arguments().get("proposalId");
                id = UUID.fromString(text);
                if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                return CallToolResult.builder().isError(true).addTextContent("Provide a valid proposal UUID.").build();
            }
            try {
                var result = execution.commitApprovedPlan(id);
                var payload = json.valueToTree(result);
                return CallToolResult.builder().isError(result.status() != State.COMMITTED)
                        .structuredContent(payload).addTextContent(payload.toString()).build();
            } catch (ApiException failure) {
                return CallToolResult.builder().isError(true).addTextContent(failure.getMessage()).build();
            }
        }));
    }
}
