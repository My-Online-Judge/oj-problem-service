-- Per-problem statistics, owned by problem-service since sub-project 2. They used to be COUNT queries over
-- t_submissions; now the consumer of oj.submission.events (SubmissionVerdictRecorded) keeps the counts, and
-- the cutover seeds them from oj-db. Only terminal verdicts are counted (never PENDING 6 or JUDGING 7).

-- How many submissions of a problem ended with each verdict (a SubmissionResult value).
CREATE TABLE t_problem_stats (
    problem_id uuid NOT NULL,
    verdict integer NOT NULL,
    submission_count bigint NOT NULL,
    CONSTRAINT t_problem_stats_pkey PRIMARY KEY (problem_id, verdict),
    CONSTRAINT fk_problem_stats_problem FOREIGN KEY (problem_id) REFERENCES t_problems(id)
);

-- Submissions already counted. Events arrive at least once; a submission is counted once.
CREATE TABLE t_processed_verdicts (
    submission_id uuid NOT NULL,
    processed_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT t_processed_verdicts_pkey PRIMARY KEY (submission_id)
);
