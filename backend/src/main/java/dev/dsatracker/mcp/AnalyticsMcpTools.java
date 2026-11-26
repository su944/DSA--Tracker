package dev.dsatracker.mcp;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import dev.dsatracker.reporting.ReportingDtos.RecentProblem;
import dev.dsatracker.reporting.ReportingService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class AnalyticsMcpTools {
    private final ReportingService reports;

    public AnalyticsMcpTools(ReportingService reports) {
        this.reports = reports;
    }

    @McpTool(name = "get_dashboard_summary",
            description = "Get all-time problem totals by current status and the percentage solved.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public DashboardSummary getDashboardSummary() {
        var summary = reports.summary();
        return new DashboardSummary(summary.total(), summary.solved(), summary.attempted(), summary.notStarted(),
                percentage(summary.solved(), summary.total()));
    }

    @McpTool(name = "get_topic_progress",
            description = "Get solved counts in the selected range and current unsolved counts per topic.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TopicProgressList getTopicProgress(
            @McpToolParam(required = false, description = "Date range: 7, 30, 90, or all. Default: all.") String range) {
        String selected = range == null ? "all" : range;
        var topics = reports.topics(selected).stream()
                .map(topic -> new TopicProgress(topic.topicId(), topic.name(), topic.total(), topic.solved(),
                        topic.remaining(), topic.total() - topic.remaining(), percentage(topic.solved(), topic.total())))
                .toList();
        return new TopicProgressList(selected, topics);
    }

    @McpTool(name = "get_difficulty_progress",
            description = "Get solved problem counts per difficulty for the selected date range.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public DifficultyProgressList getDifficultyProgress(
            @McpToolParam(required = false, description = "Date range: 7, 30, 90, or all. Default: all.") String range) {
        String selected = range == null ? "all" : range;
        var counts = new EnumMap<Difficulty, Long>(Difficulty.class);
        reports.difficulty(selected).forEach(stat -> counts.put(stat.difficulty(), stat.solved()));
        var difficulties = Arrays.stream(Difficulty.values())
                .map(difficulty -> new DifficultyProgress(difficulty, counts.getOrDefault(difficulty, 0L))).toList();
        return new DifficultyProgressList(selected, difficulties);
    }

    @McpTool(name = "get_solving_activity",
            description = "Get daily counts and the total based on current saved solved dates in the selected range.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SolvingActivity getSolvingActivity(
            @McpToolParam(required = false, description = "Date range: 7, 30, 90, or all. Default: 30.") String range) {
        String selected = range == null ? "30" : range;
        var activity = reports.activity(selected);
        var days = activity.days().stream().map(day -> new SolvingDay(day.date(), day.solved())).toList();
        return new SolvingActivity(selected, activity.startDate(), activity.endDate(), days,
                days.stream().mapToLong(SolvingDay::solvedCount).sum());
    }

    @McpTool(name = "get_recent_problems",
            description = "Get the most recently solved and modified problems in the local tracker.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public RecentProblems getRecentProblems(
            @McpToolParam(required = false, description = "Maximum entries per list, from 1 to 50. Default: 10.")
            Integer limit) {
        var recent = reports.recent("all", limit == null ? 10 : limit);
        return new RecentProblems(recent.solved().stream().map(RecentItem::from).toList(),
                recent.modified().stream().map(RecentItem::from).toList());
    }

    private static double percentage(long solved, long total) {
        return total == 0 ? 0.0 : 100.0 * solved / total;
    }

    public record DashboardSummary(long totalProblems, long solved, long attempted, long notStarted,
                                   double solvedPercentage) {}

    public record TopicProgressList(String range, List<TopicProgress> topics) {}

    public record TopicProgress(long topicId, String topicName, long totalProblems, long solvedProblems,
                                long unsolvedProblems, long solvedProblemsAllTime, double solvedPercentage) {}

    public record DifficultyProgressList(String range, List<DifficultyProgress> difficulties) {}

    public record DifficultyProgress(Difficulty difficulty, long solvedCount) {}

    public record SolvingActivity(String range, @Schema(nullable = true) LocalDate startDate, LocalDate endDate,
                                  List<SolvingDay> days, long totalSolvesInRange) {}

    public record SolvingDay(LocalDate date, long solvedCount) {}

    public record RecentProblems(List<RecentItem> recentlySolved, List<RecentItem> recentlyModified) {}

    public record RecentItem(long id, long leetcodeId, String title, ProgressStatus status,
                             @Schema(nullable = true) LocalDate solvedDate) {
        static RecentItem from(RecentProblem problem) {
            return new RecentItem(problem.id(), problem.leetcodeId(), problem.title(), problem.status(), problem.solvedDate());
        }
    }
}
