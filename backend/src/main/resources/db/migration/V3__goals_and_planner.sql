CREATE TABLE goals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
    type TEXT NOT NULL CHECK (type IN ('DAILY', 'WEEKLY', 'CUSTOM')),
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    target_count INTEGER NOT NULL CHECK (target_count > 0),
    topic_id INTEGER REFERENCES topics(id) ON DELETE RESTRICT,
    CHECK (start_date >= '0001-01-01' AND date(start_date, '+0 days') IS start_date),
    CHECK (end_date >= start_date AND date(end_date, '+0 days') IS end_date),
    CHECK (type <> 'DAILY' OR start_date = end_date),
    CHECK (type <> 'WEEKLY' OR (strftime('%w', start_date) = '1' AND date(start_date, '+6 days') IS end_date))
);

CREATE INDEX goals_by_period ON goals(start_date, id);
CREATE INDEX goals_by_topic ON goals(topic_id);

CREATE TABLE plan_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    problem_id INTEGER NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    planned_date TEXT NOT NULL,
    skipped INTEGER NOT NULL DEFAULT 0 CHECK (skipped IN (0, 1)),
    CHECK (planned_date >= '0001-01-01' AND date(planned_date, '+0 days') IS planned_date),
    UNIQUE (problem_id, planned_date)
);

CREATE INDEX plan_items_by_date ON plan_items(planned_date, id);
