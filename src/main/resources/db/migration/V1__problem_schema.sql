-- problem-db baseline: the three problem tables exactly as they exist in oj-db after judge-api V17
-- (pg_dump --schema-only of the live schema, 2026-10-04), internal foreign keys kept; nothing references
-- submissions. The data itself is copied in by judge-deployment/migrations/sp2-problem.sh at cutover.

CREATE TABLE t_problems (
    hardness_level integer NOT NULL,
    memory_limit bigint NOT NULL,
    status integer NOT NULL,
    time_limit integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    created_by character varying(255),
    description text,
    hint character varying(255),
    input_description character varying(255),
    output_description character varying(255),
    problem_slug character varying(255),
    sample_input character varying(255),
    sample_output character varying(255),
    subject text,
    title character varying(255) NOT NULL,
    updated_by character varying(255)
);

CREATE TABLE t_problem_tags (
    problem_id uuid NOT NULL,
    tag character varying(255)
);

CREATE TABLE t_test_cases (
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    problem_id uuid,
    created_by character varying(255),
    input text,
    output text,
    updated_by character varying(255),
    is_sample boolean DEFAULT false NOT NULL
);

ALTER TABLE ONLY t_problems
    ADD CONSTRAINT t_problems_pkey PRIMARY KEY (id);
ALTER TABLE ONLY t_test_cases
    ADD CONSTRAINT t_test_cases_pkey PRIMARY KEY (id);
ALTER TABLE ONLY t_problem_tags
    ADD CONSTRAINT fka8tpg3m9rbbg4qqxfxj42rey0 FOREIGN KEY (problem_id) REFERENCES t_problems(id);
ALTER TABLE ONLY t_test_cases
    ADD CONSTRAINT fkq4k9852xn1fv9b2b1p29na3jp FOREIGN KEY (problem_id) REFERENCES t_problems(id);
