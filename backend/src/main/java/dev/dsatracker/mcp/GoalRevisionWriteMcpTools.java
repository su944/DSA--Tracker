package dev.dsatracker.mcp;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.TopicService;
import dev.dsatracker.mcp.StudyPlanMcpTools.GoalDetails;
import dev.dsatracker.planning.GoalDtos.GoalInput;
import dev.dsatracker.planning.GoalDtos.GoalPatch;
import dev.dsatracker.planning.GoalDtos.GoalType;
import dev.dsatracker.planning.GoalService;
import dev.dsatracker.revision.RevisionDtos.Status;
import dev.dsatracker.revision.RevisionService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = {"spring.ai.mcp.server.enabled", "tracker.mcp-writes-enabled"}, havingValue = "true")
public class GoalRevisionWriteMcpTools {
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final GoalService goals;
    private final TopicService topics;
    private final RevisionService revisions;

    public GoalRevisionWriteMcpTools(GoalService goals, TopicService topics, RevisionService revisions) {
        this.goals = goals;
        this.topics = topics;
        this.revisions = revisions;
    }

    @McpTool(name = "create_goal", description = "Create a study goal with optional topic and difficulty restrictions. Obtain user approval first.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public GoalDetails createGoal(
            @McpToolParam(description = "Nonblank goal name, at most 100 characters.") String name,
            @McpToolParam(description = "Goal period type.") GoalType type,
            @McpToolParam(description = "Inclusive start date in YYYY-MM-DD format.") String startDate,
            @McpToolParam(required = false, description = "Inclusive end date in YYYY-MM-DD. Required for CUSTOM.") String endDate,
            @McpToolParam(description = "Positive number of solves to target.") Integer targetCount,
            @McpToolParam(required = false, description = "Existing topic name, matched case-insensitively.") String topicName,
            @McpToolParam(required = false, description = "Restrict counted solves to this difficulty.") Difficulty difficulty) {
        var input = new GoalInput(name, type, StudyWriteMcpTools.parseDate("startDate", startDate),
                optionalDate("endDate", endDate), targetCount, topicId(topicName));
        input.setDifficulty(difficulty);
        return GoalDetails.from(goals.create(input));
    }

    @Bean
    List<SyncToolSpecification> goalUpdateTools(@Qualifier("mcpServerJsonMapper") JsonMapper mapper) {
        // The argument map preserves explicit nulls for restrictions without clearing omitted fields.
        var input = mapper.readValue("""
                {
                  "type":"object", "required":["goalId"], "additionalProperties":false,
                  "properties":{
                    "goalId":{"type":"integer","minimum":1,"maximum":9223372036854775807},
                    "name":{"type":"string","maxLength":100},
                    "targetCount":{"type":"integer","minimum":1,"maximum":2147483647},
                    "topicName":{"type":["string","null"],"maxLength":80},
                    "difficulty":{"enum":["EASY","MEDIUM","HARD",null]},
                    "startDate":{"type":"string","format":"date"},
                    "endDate":{"type":"string","format":"date"}
                  }
                }
                """, JSON_OBJECT);
        var tool = Tool.builder().name("update_goal")
                .description("Update only supplied goal fields. Use null to clear a restriction. Obtain user approval first.")
                .inputSchema(input)
                .outputSchema(mapper.readValue(McpJsonSchemaGenerator.generateFromClass(GoalDetails.class), JSON_OBJECT))
                .annotations(ToolAnnotations.builder().readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(true).openWorldHint(false).build())
                .build();
        return List.of(new SyncToolSpecification(tool, (exchange, request) -> updateGoal(mapper, request.arguments())));
    }

    private CallToolResult updateGoal(JsonMapper mapper, Map<String, Object> args) {
        try {
            var id = (Number) args.get("goalId");
            var target = (Number) args.get("targetCount");
            var difficulty = (String) args.get("difficulty");
            var patch = new GoalPatch((String) args.get("name"), target == null ? null : target.intValue(),
                    optionalDate("startDate", (String) args.get("startDate")),
                    optionalDate("endDate", (String) args.get("endDate")),
                    args.containsKey("topicName"), topicId((String) args.get("topicName")),
                    args.containsKey("difficulty"), difficulty == null ? null : Difficulty.valueOf(difficulty));
            var result = GoalDetails.from(goals.patch(id.longValue(), patch));
            var json = mapper.valueToTree(result);
            return CallToolResult.builder().isError(false).structuredContent(json)
                    .addTextContent(json.toString()).build();
        } catch (ApiException exception) {
            return CallToolResult.builder().isError(true).addTextContent(exception.getMessage()).build();
        } catch (DataAccessException exception) {
            return CallToolResult.builder().isError(true).addTextContent("The goal update could not be saved.").build();
        }
    }

    @McpTool(name = "complete_revision", description = "Complete the earliest scheduled revision for a problem. Obtain user approval first.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public RevisionCompletion completeRevision(
            @McpToolParam(description = "Positive LeetCode ID.") long leetcodeId,
            @McpToolParam(required = false, description = "Review date in YYYY-MM-DD. Defaults to tracker today.") String reviewedDate) {
        var completed = revisions.completeByLeetcodeId(leetcodeId, optionalDate("reviewedDate", reviewedDate));
        var task = completed.revision();
        return new RevisionCompletion(task.id(), task.problemId(), task.leetcodeId(), task.title(), task.scheduledDate(),
                task.status(), task.completedAt(), completed.nextRevisionDate());
    }

    private Long topicId(String name) {
        return name == null ? null : topics.requireExistingId(name);
    }

    private static LocalDate optionalDate(String field, String value) {
        return value == null ? null : StudyWriteMcpTools.parseDate(field, value);
    }

    public record RevisionCompletion(long revisionId, long problemId, long leetcodeId, String title,
                                     LocalDate scheduledDate, Status status, Instant completedAt,
                                     LocalDate nextRevisionDate) {}
}
