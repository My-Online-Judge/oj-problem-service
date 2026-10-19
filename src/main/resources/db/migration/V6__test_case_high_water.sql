-- The highest test-case number a problem has ever used (the N of its <slug>/N.in files). A new test case is numbered
-- above it, so the number of a deleted case is never reused: the submissions judged against that case, the cached
-- bundle and the stored files all identify it by that number.
ALTER TABLE t_problems ADD COLUMN test_case_high_water integer NOT NULL DEFAULT 0;

UPDATE t_problems p
SET test_case_high_water = COALESCE((
    SELECT max(substring(tc.input FROM '/([0-9]{1,9})\.in$')::int)
    FROM t_test_cases tc
    WHERE tc.problem_id = p.id), 0);
