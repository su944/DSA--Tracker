package dev.dsatracker.reporting;

import dev.dsatracker.reporting.ReportingDtos.Summary;
import dev.dsatracker.reporting.ReportingDtos.Today;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/dashboard", "/api/v1/dashboard"})
class DashboardController {
    private final ReportingService reports;

    DashboardController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/summary")
    Summary summary() {
        return reports.summary();
    }

    @GetMapping("/today")
    Today today() {
        return reports.today();
    }
}
