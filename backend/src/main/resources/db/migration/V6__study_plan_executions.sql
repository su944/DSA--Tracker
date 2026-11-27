CREATE TABLE study_plan_executions (
    proposal_id TEXT PRIMARY KEY NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING_APPROVAL', 'APPROVED', 'COMMITTED', 'REJECTED', 'EXPIRED', 'FAILED')),
    snapshot TEXT NOT NULL,
    result TEXT,
    CHECK ((state IN ('COMMITTED', 'FAILED')) = (result IS NOT NULL))
);
