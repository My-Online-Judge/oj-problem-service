-- Contest registration (docs/superpowers/specs/2026-10-25-contest-enrollment-and-redesign-design.md §4.1).
-- A contest is OPEN (anyone signed in may solve it while it runs, as before) or REQUIRED (a user registers first;
-- only registered users get the problems and may submit while it runs). Every existing contest stays OPEN.
ALTER TABLE t_contests ADD COLUMN enrollment varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK (enrollment IN ('OPEN', 'REQUIRED'));

-- Who registered for which contest, and when. A row goes with its contest; a user's rows are listed on their side.
CREATE TABLE t_contest_enrollments (
    contest_id  uuid NOT NULL REFERENCES t_contests (id) ON DELETE CASCADE,
    user_id     uuid NOT NULL,
    enrolled_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (contest_id, user_id)
);
CREATE INDEX ix_contest_enrollments_user ON t_contest_enrollments (user_id);
