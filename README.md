# oj-problem-service

Problems for My Online Judge: problems, their test cases (contents in MinIO under `sources/`, judged as
bundles `<slug>/<hash>.zip` with a `<slug>/CURRENT` pointer) and per-problem statistics. Split out of
judge-api in sub-project 2b; the code kept its packages (`problem`, `testcase`).

| Port | Purpose |
|---|---|
| 8000 | API — the api-gateway routes `/api/v1/problems/**` here; reads are public, changes need `problem:*` |
| 9090 | internal gRPC `oj.problem.v1.ProblemInternal` (oj-common's `problem_internal.proto`) — oj-net only |
| 8081 | actuator: `/actuator/health`, `/actuator/prometheus` (dev/prod profiles) |

Configuration comes from `judge-deployment/.env.problem` (see `.env.problem.example` there): its own
Postgres (`problem-db`, schema by Flyway `V1` = the live tables, `V2` = statistics), plus MinIO and the
JWKS URI through the compose file.

Internal API: `GetJudgeSpec` (NOT_FOUND for an unknown or deleted slug, FAILED_PRECONDITION when no
bundle is published, UNAVAILABLE when MinIO cannot be read) and `GetSampleTestCases` (sample cases only;
deleted problems included; unknown id → empty). Every call must carry `x-oj-service-token` =
`PROBLEM_RPC_TOKEN` (32+ characters; the service does not start without it).

Statistics: `t_problem_stats` holds, per problem, how many submissions ended with each verdict. Only
terminal verdicts count; a problem's total and accepted numbers are sums over it. The consumer group
`problem-service-stats` keeps it from `oj.submission.events` (`SubmissionVerdictRecorded`, published by
judge-api's outbox): one transaction per event, counted once per submission (`t_processed_verdicts`),
verdicts of unknown problems skipped. A record it cannot read goes straight to
`oj.submission.events.dlq`; any other failure (the database restarting, a lock) is retried with a growing
pause, 1 s doubling to 30 s, for about 5.5 minutes before it is dead-lettered too. Each dead-lettered record
increments `oj_stats_dead_lettered_total` (alert `ProblemStatsDeadLettered`).

Replaying the dead letters once the cause is fixed — safe, a submission is counted once however often its
event arrives:

    docker exec oj-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic oj.submission.events.dlq --from-beginning --timeout-ms 5000 \
        --property print.key=true --property key.separator='|' > dlq.txt
    docker exec -i oj-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
        --topic oj.submission.events --property parse.key=true --property key.separator='|' < dlq.txt

## Build and test

`oj-common` must be installed first (`./mvnw install` in the sibling `oj-common` repo). Use `./mvnw`
(Maven 3.9.9): oj-common's protobuf plugin needs Maven 3.9.6 or later.

    ./mvnw verify

Tests that run native SQL use a real Postgres through Testcontainers (`support/PostgresTest`); the MinIO
tests use `ghcr.io/my-online-judge/minio:RELEASE.2025-09-07T16-13-09Z`.

Docker builds compile `oj-common` from the named build context:
`docker build --build-context oj-common=../oj-common .`
