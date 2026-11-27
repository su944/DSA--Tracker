package dev.dsatracker.planning;

import dev.dsatracker.planning.PlannerWriteApproval.Action;
import dev.dsatracker.planning.PlannerWriteApproval.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = {"spring.ai.mcp.server.enabled", "tracker.mcp-writes-enabled"}, havingValue = "true")
class PlannerWriteApprovalController {
    private final StudyPlanApprovalAccess access;
    private final PlannerWriteApproval approvals;

    PlannerWriteApprovalController(StudyPlanApprovalAccess access, PlannerWriteApproval approvals) {
        this.access = access;
        this.approvals = approvals;
    }

    @PostMapping("/api/planner-write-approvals/{operation}/{id}/{date}")
    ResponseEntity<Action> approve(@PathVariable Operation operation, @PathVariable long id,
                                   @PathVariable LocalDate date, HttpServletRequest request) {
        access.requireOperator(request);
        var action = new Action(operation, id, date);
        approvals.authorize(action);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action);
    }
}
