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
Postgres (`problem-db`, schema by Flyway `V1` = the live tables, `V2` = statistics), plus Kafka and MinIO
through the compose file; the JWKS URI is fixed in the `tls` profile (`https://identity-service:8000/.well-known/jwks.json`,
fetched over mutual TLS with the `oj` bundle), and so is identity's base URL for public profiles, the contest
scoreboard's usernames (`oj.identity.base-url`, read through oj-common's `PublicUserClient` and cached 5 minutes; while
identity is down, users not in that cache are listed by id only). MinIO gets the service's own key (test-case bucket only); under the `tls` profile
it is reached over https and its certificate is verified against the `oj` bundle's CA (`minio.ssl-bundle`).

Internal API: `GetJudgeSpec` (NOT_FOUND for an unknown or deleted slug, FAILED_PRECONDITION when no
bundle is published, UNAVAILABLE when MinIO cannot be read; with a `contest_slug`, a spec with `contest_id` only
while that PUBLISHED contest runs and contains the problem, INACTIVE included, else FAILED_PRECONDITION with the
trailer `oj-reason: CONTEST_NOT_RUNNING`), `GetSampleTestCases` (sample cases only; deleted problems included;
unknown id → empty) and `ListVisibleSlugs` (which of at most 100 slugs are ACTIVE; more → INVALID_ARGUMENT).
Under the `tls` profile callers authenticate by client
certificate: 9090 serves TLS, requires a CA-signed certificate and answers only the CNs in
`oj.tls.grpc-allowed-clients` (submission-service; PERMISSION_DENIED otherwise). A set `oj.rpc.token`
(`PROBLEM_RPC_TOKEN`, 32+ characters) additionally requires `x-oj-service-token` on every call. With neither,
the service does not start.

Statistics: `t_problem_stats` holds, per problem, how many submissions ended with each verdict. Only
terminal verdicts count; a problem's total and accepted numbers are sums over it. The consumer group
`problem-service-stats` keeps it from `oj.submission.events` (`SubmissionVerdictRecorded`, published by
submission-service's outbox): one transaction per event, counted once per submission (`t_processed_verdicts`),
verdicts of unknown problems skipped. The same transaction keeps contest scores (`t_contest_scores`): a
verdict that names its contest, submitter and submit time is noted on its `t_processed_verdicts` row, and the
submitter's score on that problem is recomputed from all such rows (`accepted_at` = the first accepted submit
time; compile and system errors never count). A record it cannot read goes straight to
`oj.submission.events.dlq`; any other failure (the database restarting, a lock) is retried with a growing
pause, 1 s doubling to 30 s, for about 5.5 minutes before it is dead-lettered too. Each dead-lettered record
increments `oj_stats_dead_lettered_total` (alert `ProblemStatsDeadLettered`).

Replaying the dead letters once the cause is fixed — from the `judge-deployment` repo:

    scripts/replay-dlq.sh oj.submission.events.dlq oj.submission.events [--max N]

The script copies the records, keys included, through the broker's loopback listener; its consumer group
`dlq-replay` commits offsets, so a second run copies only records not copied before. It is safe for first
verdicts: a submission is counted once however often its event arrives. A REJUDGE event replayed after a newer
rejudge of the same submission moves the statistics, and its contest score, back to the older verdict (the event
has no ordering field): replay rejudge events only when no newer rejudge happened for those submissions, or run
`scripts/stats-check.sh` afterwards (it checks the counts, not contest scores).

## Build and test

`oj-common` must be installed first (`./mvnw install` in the sibling `oj-common` repo). Use `./mvnw`
(Maven 3.9.9): oj-common's protobuf plugin needs Maven 3.9.6 or later.

    ./mvnw verify

Tests that run native SQL use a real Postgres through Testcontainers (`support/PostgresTest`); the MinIO
tests use `ghcr.io/my-online-judge/minio:RELEASE.2025-09-07T16-13-09Z`.

Docker builds compile `oj-common` from the named build context:
`docker build --build-context oj-common=../oj-common .`
