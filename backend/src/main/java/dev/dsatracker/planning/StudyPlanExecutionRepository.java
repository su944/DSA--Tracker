package dev.dsatracker.planning;

import dev.dsatracker.planning.StudyPlanExecutionService.CommitResult;
import dev.dsatracker.planning.StudyPlanExecutionService.State;
import dev.dsatracker.planning.StudyPlanRecommendationService.StoredProposal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class StudyPlanExecutionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    StudyPlanExecutionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<Execution> find(UUID id) {
        return jdbc.query("""
                SELECT state, snapshot, result FROM study_plan_executions WHERE proposal_id = ?
                """, (row, index) -> {
            String result = row.getString("result");
            return new Execution(State.valueOf(row.getString("state")),
                    json.readValue(row.getString("snapshot"), StoredProposal.class),
                    result == null ? null : json.readValue(result, CommitResult.class));
        }, id.toString()).stream().findFirst();
    }

    void request(UUID id, StoredProposal proposal) {
        jdbc.update("""
                INSERT INTO study_plan_executions(proposal_id,state,snapshot)
                VALUES (?, 'PENDING_APPROVAL', ?)
                """, id.toString(), json.writeValueAsString(proposal));
    }

    void transition(UUID id, State from, State to, CommitResult result) {
        if (!from.allows(to)) throw new IllegalStateException("Invalid study plan state transition.");
        int changed = jdbc.update("""
                UPDATE study_plan_executions SET state = ?, result = ? WHERE proposal_id = ? AND state = ?
                """, to.name(), result == null ? null : json.writeValueAsString(result), id.toString(), from.name());
        if (changed != 1) throw new IllegalStateException("Study plan state changed during execution.");
    }

    record Execution(State state, StoredProposal snapshot, CommitResult result) {}
}
