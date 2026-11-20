CREATE TABLE problems (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    leetcode_id INTEGER NOT NULL UNIQUE CHECK (leetcode_id > 0),
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 255),
    slug TEXT NOT NULL CHECK (length(slug) BETWEEN 1 AND 255),
    url TEXT NOT NULL,
    difficulty TEXT NOT NULL CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE topics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE CHECK (length(trim(name)) BETWEEN 1 AND 80),
    name_key TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);

CREATE TABLE problem_topics (
    problem_id INTEGER NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE RESTRICT,
    PRIMARY KEY (problem_id, topic_id)
);

CREATE TABLE problem_progress (
    problem_id INTEGER PRIMARY KEY REFERENCES problems(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('NOT_STARTED', 'ATTEMPTED', 'SOLVED')),
    attempted_date TEXT,
    solved_date TEXT,
    comments TEXT NOT NULL DEFAULT '' CHECK (length(comments) <= 10000),
    updated_at TEXT NOT NULL,
    CHECK (
        (status = 'NOT_STARTED' AND attempted_date IS NULL AND solved_date IS NULL)
        OR (status = 'ATTEMPTED' AND attempted_date IS NOT NULL AND solved_date IS NULL)
        OR (status = 'SOLVED' AND solved_date IS NOT NULL)
    ),
    CHECK (attempted_date IS NULL OR solved_date IS NULL OR attempted_date <= solved_date)
);

CREATE INDEX problem_topics_by_topic ON problem_topics(topic_id, problem_id);
CREATE INDEX problems_by_difficulty ON problems(difficulty);
CREATE INDEX progress_by_status_date ON problem_progress(status, solved_date);
