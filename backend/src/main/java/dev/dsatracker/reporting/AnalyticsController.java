package dev.dsatracker.reporting;

import dev.dsatracker.reporting.ReportingDtos.Activity;
import dev.dsatracker.reporting.ReportingDtos.DifficultyStats;
import dev.dsatracker.reporting.ReportingDtos.Recent;
import dev.dsatracker.reporting.ReportingDtos.TopicStats;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/analytics", "/api/v1/analytics"})
class AnalyticsController {
    private final ReportingService reports;

    AnalyticsController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/topics")
    List<TopicStats> topics(@RequestParam(defaultValue = "all") String range) {
        return reports.topics(range);
    }

    @GetMapping("/difficulty")
    List<DifficultyStats> difficulty(@RequestParam(defaultValue = "all") String range) {
        return reports.difficulty(range);
    }

    @GetMapping("/activity")
    Activity activity(@RequestParam(defaultValue = "all") String range) {
        return reports.activity(range);
    }

    @GetMapping("/recent")
    Recent recent(@RequestParam(defaultValue = "all") String range, @RequestParam(defaultValue = "10") int limit) {
        return reports.recent(range, limit);
    }
}
