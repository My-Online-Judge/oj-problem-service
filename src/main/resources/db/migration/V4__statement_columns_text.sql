-- Statement fields were varchar(255) (Hibernate default); real statements are longer. Pure widening: metadata-only on Postgres 16.
ALTER TABLE t_problems ALTER COLUMN hint TYPE text;
ALTER TABLE t_problems ALTER COLUMN input_description TYPE text;
ALTER TABLE t_problems ALTER COLUMN output_description TYPE text;
ALTER TABLE t_problems ALTER COLUMN sample_input TYPE text;
ALTER TABLE t_problems ALTER COLUMN sample_output TYPE text;
