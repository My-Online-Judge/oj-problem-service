-- The verdict each submission is counted under in t_problem_stats, so that a rejudge can move its count from the
-- old verdict to the new one. NULL on the rows counted before V5 until judge-deployment's
-- migrations/sp11-last-status.sh backfills them from submission-db; a rejudge of a row still NULL leaves the counts
-- alone rather than risk counting the submission twice.
ALTER TABLE t_processed_verdicts ADD COLUMN last_status integer NULL;
