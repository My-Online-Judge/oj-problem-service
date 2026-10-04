# oj-problem-service

Problems for My Online Judge: problems, their test cases (contents in MinIO under `sources/`, judged as
bundles `<slug>/<hash>.zip` with a `<slug>/CURRENT` pointer) and per-problem statistics. Split out of
judge-api in sub-project 2b; the code kept its packages (`problem`, `testcase`).

| Port | Purpose |
|---|---|
| 8000 | API — the api-gateway routes `/api/v1/problems/**` here; reads are public, changes need `problem:*` |
| 8081 | actuator: `/actuator/health`, `/actuator/prometheus` (dev/prod profiles) |

Configuration comes from `judge-deployment/.env.problem` (see `.env.problem.example` there): its own
Postgres (`problem-db`, schema by Flyway `V1` = the live tables, `V2` = statistics), plus MinIO and the
JWKS URI through the compose file.

Statistics: `t_problem_stats` holds, per problem, how many submissions ended with each verdict. Only
terminal verdicts count; a problem's total and accepted numbers are sums over it.

## Build and test

`oj-common` must be installed first (`./mvnw install` in the sibling `oj-common` repo). Use `./mvnw`
(Maven 3.9.9): oj-common's protobuf plugin needs Maven 3.9.6 or later.

    ./mvnw verify

Tests that run native SQL use a real Postgres through Testcontainers (`support/PostgresTest`); the MinIO
tests use `ghcr.io/my-online-judge/minio:RELEASE.2025-09-07T16-13-09Z`.

Docker builds compile `oj-common` from the named build context:
`docker build --build-context oj-common=../oj-common .`
