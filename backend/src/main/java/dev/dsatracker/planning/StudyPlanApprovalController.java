package dev.dsatracker.planning;

import dev.dsatracker.planning.StudyPlanExecutionService.ApprovalView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/study-plan-approvals/{proposalId}")
@ConditionalOnProperty(name = {"spring.ai.mcp.server.enabled", "tracker.mcp-writes-enabled"}, havingValue = "true")
class StudyPlanApprovalController {
    private final StudyPlanApprovalAccess access;
    private final StudyPlanExecutionService execution;

    StudyPlanApprovalController(StudyPlanApprovalAccess access, StudyPlanExecutionService execution) {
        this.access = access;
        this.execution = execution;
    }

    @PostMapping("/request")
    ResponseEntity<ApprovalView> request(@PathVariable UUID proposalId, HttpServletRequest request) {
        access.requireOperator(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(execution.requestApproval(proposalId));
    }

    @PostMapping("/approve")
    ResponseEntity<ApprovalView> approve(@PathVariable UUID proposalId, HttpServletRequest request) {
        access.requireOperator(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(execution.decide(proposalId, true));
    }

    @PostMapping("/reject")
    ResponseEntity<ApprovalView> reject(@PathVariable UUID proposalId, HttpServletRequest request) {
        access.requireOperator(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(execution.decide(proposalId, false));
    }
}
