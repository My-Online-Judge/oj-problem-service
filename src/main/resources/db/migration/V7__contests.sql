CREATE TABLE t_contests (
    id uuid PRIMARY KEY,
    slug varchar(64) NOT NULL,
    title varchar(255) NOT NULL,
    description text,
    starts_at timestamp(6) with time zone NOT NULL,
    ends_at timestamp(6) with time zone NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL,
    created_by varchar(255), updated_by varchar(255),
    CONSTRAINT ck_contests_window CHECK (ends_at > starts_at)
);
CREATE UNIQUE INDEX ux_contests_slug ON t_contests (slug);
CREATE TABLE t_contest_problems (
    contest_id uuid NOT NULL REFERENCES t_contests (id) ON DELETE CASCADE,
    problem_id uuid NOT NULL REFERENCES t_problems (id),
    label char(1) NOT NULL CHECK (label BETWEEN 'A' AND 'Z'),
    position integer NOT NULL,
    PRIMARY KEY (contest_id, label),
    UNIQUE (contest_id, problem_id)
);
CREATE TABLE t_contest_scores (
    contest_id uuid NOT NULL REFERENCES t_contests (id) ON DELETE CASCADE,
    user_id uuid NOT NULL,
    problem_id uuid NOT NULL,
    attempts_before_ac integer NOT NULL DEFAULT 0,
    accepted_at timestamp(6) with time zone,
    PRIMARY KEY (contest_id, user_id, problem_id)
);
-- Per-submission facts the contest scores are recomputed from (Task 12).
ALTER TABLE t_processed_verdicts
    ADD COLUMN contest_id uuid, ADD COLUMN user_id uuid, ADD COLUMN problem_id uuid,
    ADD COLUMN submitted_at timestamp(6) with time zone;
CREATE INDEX ix_processed_verdicts_contest ON t_processed_verdicts (contest_id, user_id, problem_id)
    WHERE contest_id IS NOT NULL;
