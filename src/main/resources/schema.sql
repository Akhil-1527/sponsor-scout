CREATE TABLE IF NOT EXISTS lca (
    case_number  TEXT PRIMARY KEY,
    employer     TEXT NOT NULL,
    employer_key TEXT NOT NULL,
    job_title    TEXT,
    soc_code     TEXT,
    soc_title    TEXT,
    city         TEXT,
    state        TEXT,
    wage         INTEGER,
    wage_level   TEXT,
    new_hires    INTEGER,
    transfers    INTEGER,
    decided      TEXT
);
CREATE INDEX IF NOT EXISTS lca_employer ON lca (employer_key);
-- Word index over job titles for role searches. Its rowids point at lca rows, so it is rebuilt after every ingest.
CREATE VIRTUAL TABLE IF NOT EXISTS lca_fts USING fts5(job_title);

CREATE TABLE IF NOT EXISTS profile (
    id      INTEGER PRIMARY KEY CHECK (id = 1),
    roles   TEXT,
    skills  TEXT,
    years   INTEGER,
    states  TEXT,
    visa    TEXT,
    updated TEXT
);

CREATE TABLE IF NOT EXISTS saved_job (
    job_id    TEXT PRIMARY KEY,
    company   TEXT,
    title     TEXT,
    location  TEXT,
    url       TEXT,
    status    TEXT NOT NULL CHECK (status IN ('saved', 'applied', 'interviewing', 'offer', 'rejected', 'withdrawn')),
    note      TEXT,
    follow_up TEXT,
    updated   TEXT
);

CREATE TABLE IF NOT EXISTS seen_job (
    job_id     TEXT PRIMARY KEY,
    first_seen TEXT
);
