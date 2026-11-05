-- The newest generation (the submission's rejudge_count, carried by SubmissionVerdictRecorded from oj-common 0.10.0)
-- counted for each submission, so that a verdict of an older generation arriving later (two outbox relay passes, a
-- dead letter replayed after a newer rejudge) is ignored instead of moving the count back to the older verdict.
-- NULL on the rows counted before V10, and while only verdicts without a generation have arrived: the first verdict
-- that carries one is then applied and sets it.
ALTER TABLE t_processed_verdicts ADD COLUMN last_generation integer NULL;
