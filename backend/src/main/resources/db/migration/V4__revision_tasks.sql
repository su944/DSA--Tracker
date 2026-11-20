CREATE TABLE revision_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    problem_id INTEGER NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    scheduled_date TEXT NOT NULL CHECK (scheduled_date >= '0001-01-01' AND date(scheduled_date, '+0 days') IS scheduled_date),
    completed_at TEXT,
    status TEXT NOT NULL CHECK (status IN ('SCHEDULED', 'COMPLETED', 'CANCELLED')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    initial_schedule INTEGER NOT NULL DEFAULT 0 CHECK (initial_schedule IN (0, 1)),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX revision_active_problem_date ON revision_tasks(problem_id, scheduled_date) WHERE status = 'SCHEDULED';
CREATE INDEX revision_tasks_by_status_date ON revision_tasks(status, scheduled_date, id);
CREATE INDEX revision_tasks_by_problem ON revision_tasks(problem_id);
