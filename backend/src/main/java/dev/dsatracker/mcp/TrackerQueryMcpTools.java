package dev.dsatracker.mcp;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.catalog.TopicService;
import dev.dsatracker.planning.GoalDtos.GoalType;
import dev.dsatracker.planning.PlannerDtos.TaskState;
import dev.dsatracker.query.TrackerQueryRequest.Domain;
import dev.dsatracker.query.TrackerQueryRequest.Report;
import dev.dsatracker.query.TrackerQueryService;
import dev.dsatracker.query.TrackerQueryValidator;
import dev.dsatracker.query.TrackerQueryValidator.InvalidQuery;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
class TrackerQueryMcpTools {
    private static final ToolAnnotations READ_ONLY = ToolAnnotations.builder().readOnlyHint(true)
            .destructiveHint(false).idempotentHint(true).openWorldHint(false).build();

    @Bean
    @org.springframework.context.annotation.Primary
    McpSyncServerCustomizer trackerValidationCustomizer(Environment environment) {
        boolean legacy = "LEGACY".equals(environment.getProperty("tracker.mcp-tool-profile", "LEGACY"));
        // Existing tool specs retain SDK validation in the registration wrapper. The query tool
        // validates against its typed contract to return domain-specific structured errors.
        return server -> server.immediateExecution(true).validateToolInputs(legacy);
    }

    @Bean
    List<SyncToolSpecification> trackerQueryTools(TrackerQueryService queries, TopicService topics,
                                                   @Qualifier("mcpServerJsonMapper") JsonMapper json) {
        var query = Tool.builder().name("query_tracker").description("Query bounded local tracker data with typed domain filters.")
                .inputSchema(TrackerQueryValidator.schema())
                .outputSchema(Map.of("type", "object", "required", List.of("domain", "returnedCount", "hasMore", "items"),
                        "properties", Map.of("domain", Map.of("type", "string"), "page", Map.of("type", "integer"),
                                "returnedCount", Map.of("type", "integer"), "hasMore", Map.of("type", "boolean"),
                                "items", Map.of("type", "array"), "truncated", Map.of("type", "boolean"))))
                .annotations(READ_ONLY).build();
        var describe = Tool.builder().name("describe_tracker").description("Discover query filters, enums and local topic names.")
                .inputSchema(Map.of("type", "object", "additionalProperties", false, "properties", Map.of()))
                .annotations(READ_ONLY).build();
        return List.of(new SyncToolSpecification(query, (exchange, request) -> {
            var params = request.arguments();
            if (params == null || !(params.get("domain") instanceof String name)) {
                return output(json, new TrackerQueryValidator.Error(TrackerQueryValidator.Code.INVALID_DOMAIN,
                        "Select a supported tracker domain."), true);
            }
            Domain domain;
            try {
                domain = Domain.valueOf(name);
            } catch (IllegalArgumentException failure) {
                return output(json, new TrackerQueryValidator.Error(TrackerQueryValidator.Code.INVALID_DOMAIN,
                        "Select a supported tracker domain."), true);
            }
            if (params.size() != 2 || !params.containsKey("query")) {
                return output(json, new TrackerQueryValidator.Error(TrackerQueryValidator.Code.INVALID_QUERY,
                        "Provide only domain and query."), true);
            }
            try {
                return output(json, queries.execute(domain, json.valueToTree(params.get("query"))), false);
            } catch (InvalidQuery failure) {
                return output(json, failure.error(), true);
            }
        }), new SyncToolSpecification(describe, (exchange, request) -> {
            var names = topics.search(null, 51);
            return output(json, Map.of("domains", Domain.values(), "querySchema", TrackerQueryValidator.schema(),
                    "difficulty", Difficulty.values(), "progressStatuses", ProgressStatus.values(), "goalTypes", GoalType.values(),
                    "plannerStates", TaskState.values(), "reports", Report.values(),
                    "topicNames", names.stream().limit(50).map(topic -> topic.name()).toList(),
                    "topicsTruncated", names.size() > 50, "limits", Map.of("page", 10000, "limit", 100, "queryBytes", 8192)), false);
        }));
    }

    private static CallToolResult output(JsonMapper json, Object value, boolean error) {
        var node = json.valueToTree(value);
        // Structured content is the full payload. Text-only clients get a short pointer.
        return CallToolResult.builder().isError(error).structuredContent(node)
                .addTextContent(error ? node.toString() : "See structuredContent.").build();
    }
}
