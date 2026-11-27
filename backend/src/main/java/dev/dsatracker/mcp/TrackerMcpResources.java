package dev.dsatracker.mcp;

import dev.dsatracker.planning.StudyPlanApprovalAccess;
import dev.dsatracker.reporting.ReportingService;
import dev.dsatracker.revision.RevisionService;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
class TrackerMcpResources {
    private static final String JSON = "application/json";
    private static final String CAPABILITIES = "tracker://capabilities";
    private static final String TOPICS = "tracker://topics";
    private static final String PROGRESS = "tracker://progress/summary";
    private static final String PLANNING = "tracker://planning/rules";
    private static final String REVISIONS = "tracker://revision/policy";

    private final ReportingService reports;
    private final ObjectMapper json;
    private final String name;
    private final boolean writes;
    private final StudyPlanApprovalAccess approval;

    TrackerMcpResources(ReportingService reports, ObjectMapper json,
                        @Value("${spring.ai.mcp.server.name}") String name,
                        @Value("${tracker.mcp-writes-enabled}") boolean writes, StudyPlanApprovalAccess approval) {
        this.reports = reports;
        this.json = json;
        this.name = name;
        this.writes = writes;
        this.approval = approval;
    }

    @McpResource(uri = CAPABILITIES, name = "capabilities", mimeType = JSON,
            description = "Supported DSA Tracker workflows, capabilities and limits.")
    public ReadResourceResult capabilities() {
        return resource(CAPABILITIES, Map.of("serverName", name, "features", List.of("tools", "resources", "prompts"),
                "workflows", List.of("catalog", "analytics", "goals", "revisions", "LeetCode discovery and import", "study plans"),
                "writesEnabled", writes, "serverEnforcedApprovalEnabled", writes && approval.configured(),
                "limits", Map.of("searchPageSize", 100, "topicRows", 50, "goalRows", 50,
                        "revisionRows", 100, "activityDays", 365, "importCandidates", 500, "proposalEntries", 30)));
    }

    @McpResource(uri = TOPICS, name = "topics", mimeType = JSON,
            description = "Bounded local topic counts.")
    public ReadResourceResult topics() {
        return resource(TOPICS, topicPage());
    }

    @McpResource(uri = PROGRESS, name = "progress_summary", mimeType = JSON,
            description = "Current status totals with bounded topic and difficulty aggregates.")
    public ReadResourceResult progress() {
        return resource(PROGRESS, Map.of("summary", reports.summary(), "topics", topicPage(),
                "difficulty", reports.difficulty("all")));
    }

    @McpResource(uri = PLANNING, name = "planning_rules", mimeType = JSON,
            description = "Study recommendation and planner approval rules.")
    public ReadResourceResult planningRules() {
        return resource(PLANNING, Map.of("dateFormat", "YYYY-MM-DD", "dateDefault", "tracker today",
                "recommendationDays", Map.of("min", 1, "max", 30),
                "problemsPerDay", Map.of("min", 1, "max", 5), "candidateLimit", Map.of("default", 15, "max", 30),
                "eligibility", "Topic matches any requested topic. Solved and scheduled problems are excluded by default.",
                "duplicates", "A proposal contains distinct problems. Existing problem/date pairs cannot be scheduled again.",
                "approval", "A local operator must approve the exact batch proposal or exact direct planner action."));
    }

    @McpResource(uri = REVISIONS, name = "revision_policy", mimeType = JSON,
            description = "Eligibility and completion rules for revisions.")
    public ReadResourceResult revisionPolicy() {
        return resource(REVISIONS, Map.of("firstReviewDays", RevisionService.INTERVAL_DAYS,
                "followUpDays", RevisionService.INTERVAL_DAYS,
                "eligibility", "Only solved problems can be scheduled or completed.",
                "completion", "Completing a scheduled revision preserves the solve date and schedules a follow-up from the review date."));
    }

    private Map<String, Object> topicPage() {
        var topics = reports.topics("all", 51);
        return Map.of("items", topics.stream().limit(50).toList(), "truncated", topics.size() > 50);
    }

    private ReadResourceResult resource(String uri, Object payload) {
        return new ReadResourceResult(List.of(new TextResourceContents(uri, JSON, json.writeValueAsString(payload))));
    }
}
