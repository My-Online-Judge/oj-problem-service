-- The slug is a problem's public identity and is never reused: a deleted problem keeps it, because submissions
-- keep it. ProblemService checked this; the database now guarantees it, so two concurrent creates cannot both win.
ALTER TABLE t_problems ALTER COLUMN problem_slug SET NOT NULL;
CREATE UNIQUE INDEX ux_problems_slug ON t_problems (problem_slug);

-- Foreign keys Postgres does not index by itself, read on every problem page and every sample lookup.
CREATE INDEX ix_problem_tags_problem_id ON t_problem_tags (problem_id);
CREATE INDEX ix_test_cases_problem_id ON t_test_cases (problem_id);
