package dev.dsatracker.mcp;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.planning.StudyPlanRecommendationService;
import dev.dsatracker.planning.StudyPlanRecommendationService.Proposal;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class RecommendStudyPlanMcpTool {
    private final StudyPlanRecommendationService plans;

    public RecommendStudyPlanMcpTool(StudyPlanRecommendationService plans) {
        this.plans = plans;
    }

    @McpTool(name = "recommend_study_plan",
            description = "Propose a compact study schedule from eligible local problems without saving planner entries.",
            generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Proposal recommend(
            @McpToolParam(required = false, description = "Existing topic names, matched case-insensitively with OR semantics.")
            List<String> topicNames,
            @McpToolParam(required = false, description = "Problem difficulty.") Difficulty difficulty,
            @McpToolParam(required = false, description = "ISO start date. Defaults to tracker today.") String startDate,
            @McpToolParam(required = false, description = "Days, 1–30. Default 5.") Integer days,
            @McpToolParam(required = false, description = "Problems per day, 1–5. Default 1.") Integer problemsPerDay,
            @McpToolParam(required = false, description = "Exclude solved problems. Default true.") Boolean excludeSolved,
            @McpToolParam(required = false, description = "Exclude problems with planner entries on any date. Default true.")
            Boolean excludeScheduled,
            @McpToolParam(required = false, description = "Candidate and response cap, 1–30. Default 15.") Integer candidateLimit) {
        var start = startDate == null ? null : StudyWriteMcpTools.parseDate("startDate", startDate);
        return plans.recommend(topicNames, difficulty, start, days == null ? 5 : days,
                problemsPerDay == null ? 1 : problemsPerDay, !Boolean.FALSE.equals(excludeSolved),
                !Boolean.FALSE.equals(excludeScheduled), candidateLimit == null ? 15 : candidateLimit);
    }
}
