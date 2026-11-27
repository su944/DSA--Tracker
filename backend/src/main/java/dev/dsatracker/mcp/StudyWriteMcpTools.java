package dev.dsatracker.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.CatalogDtos.ProgressPatch;
import dev.dsatracker.catalog.ProblemService;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.mcp.StudyPlanMcpTools.ScheduledProblem;
import dev.dsatracker.planning.PlannerService;
import dev.dsatracker.planning.PlannerWriteApproval;
import dev.dsatracker.planning.PlannerWriteApproval.Operation;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
public class StudyWriteMcpTools {
    private static final String DATE_PATTERN = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final Map<String, Object> OPTIONAL_DATE = Map.of(
            "type", List.of("string", "null"), "pattern", "^" + DATE_PATTERN + "$");
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final ProblemService problems;
    private final PlannerService planner;
    private final PlannerWriteApproval approvals;

    public StudyWriteMcpTools(ProblemService problems, PlannerService planner, PlannerWriteApproval approvals) {
        this.problems = problems;
        this.planner = planner;
        this.approvals = approvals;
    }

    @Bean
    List<SyncToolSpecification> progressWriteTools(@Qualifier("mcpServerJsonMapper") JsonMapper mapper) {
        // An explicit schema preserves nullable dates and supplies the original argument keys.
        var input = Map.<String, Object>of(
                "type", "object", "required", List.of("leetcodeId", "status"), "additionalProperties", false,
                "properties", Map.of(
                        "leetcodeId", Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE),
                        "status", Map.of("type", "string", "enum", List.of(ProgressStatus.values())),
                        "attemptedDate", OPTIONAL_DATE,
                        "solvedDate", OPTIONAL_DATE,
                        "comments", Map.of("type", "string", "maxLength", 10000)));
        var tool = Tool.builder().name("update_problem_progress")
                .description("Update saved problem progress and optionally replace dates or comments. Obtain user approval first.")
                .inputSchema(mapper.convertValue(input, JSON_OBJECT))
                .outputSchema(mapper.readValue(McpJsonSchemaGenerator.generateFromClass(UpdatedProgress.class), JSON_OBJECT))
                .annotations(ToolAnnotations.builder().readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(false).build())
                .build();
        return List.of(new SyncToolSpecification(tool,
                (exchange, request) -> updateProgress(mapper, request.arguments())));
    }

    private CallToolResult updateProgress(JsonMapper mapper, Map<String, Object> arguments) {
        try {
            var id = mapper.valueToTree(arguments.get("leetcodeId"));
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() < 1) {
                throw ApiException.invalid("leetcodeId", "LeetCode ID must be a positive integer.");
            }
            var patch = new ProgressPatch();
            patch.setStatus(ProgressStatus.valueOf((String) arguments.get("status")));
            if (arguments.containsKey("attemptedDate")) {
                patch.setAttemptedDate(patchDate("attemptedDate", (String) arguments.get("attemptedDate")));
            }
            if (arguments.containsKey("solvedDate")) {
                patch.setSolvedDate(patchDate("solvedDate", (String) arguments.get("solvedDate")));
            }
            if (arguments.containsKey("comments")) {
                var comments = (String) arguments.get("comments");
                if (comments.length() > 10000) {
                    throw ApiException.invalid("comments", "Comments must be at most 10000 characters.");
                }
                patch.setComments(comments);
            }
            var progress = problems.updateProgressByLeetcodeId(id.asLong(), patch);
            var result = new UpdatedProgress(id.asLong(), progress.status(), progress.attemptedDate(),
                    progress.solvedDate(), progress.comments(), progress.updatedAt());
            return CallToolResult.builder().isError(false)
                    .structuredContent(mapper.convertValue(result, JSON_OBJECT))
                    .addTextContent(mapper.writeValueAsString(result)).build();
        } catch (ApiException exception) {
            return CallToolResult.builder().isError(true).addTextContent(exception.getMessage()).build();
        } catch (DataAccessException exception) {
            return CallToolResult.builder().isError(true)
                    .addTextContent("The progress update could not be saved.").build();
        }
    }

    @McpTool(name = "schedule_problem", description = "Schedule a local problem on a date. Obtain user approval first.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public ScheduledProblem scheduleProblem(
            @McpToolParam(description = "Positive LeetCode ID.") long leetcodeId,
            @McpToolParam(description = "Scheduled date in YYYY-MM-DD format.") String date) {
        var day = parseDate("date", date);
        approvals.require(Operation.SCHEDULE, leetcodeId, day);
        return ScheduledProblem.from(planner.scheduleByLeetcodeId(leetcodeId, day));
    }

    @McpTool(name = "reschedule_problem", description = "Move an existing planner entry. Obtain user approval first.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ScheduledProblem rescheduleProblem(
            @McpToolParam(description = "Positive internal planner entry ID.") long plannerEntryId,
            @McpToolParam(description = "New scheduled date in YYYY-MM-DD format.") String date) {
        var day = parseDate("date", date);
        approvals.require(Operation.RESCHEDULE, plannerEntryId, day);
        return ScheduledProblem.from(planner.reschedule(plannerEntryId, day));
    }

    private static LocalDate patchDate(String field, String date) {
        return date == null ? null : parseDate(field, date);
    }

    static LocalDate parseDate(String field, String value) {
        if (value != null && value.matches(DATE_PATTERN)) {
            try {
                var date = LocalDate.parse(value);
                if (date.getYear() > 0) {
                    return date;
                }
            } catch (DateTimeParseException ignored) {
                // Report the same input error for invalid calendar dates.
            }
        }
        throw ApiException.invalid(field, field + " must be a valid date in YYYY-MM-DD format (year 0001 to 9999).");
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UpdatedProgress(long leetcodeId, ProgressStatus status, @Schema(nullable = true) LocalDate attemptedDate,
                                  @Schema(nullable = true) LocalDate solvedDate, String comments, Instant updatedAt) {}
}
