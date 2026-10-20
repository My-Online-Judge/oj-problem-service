package vn.thanhtuanle.contest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.problem.ProblemStatsRecorder;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Contest scores kept from verdict events, fed through the statistics' recorder as the consumer feeds them. A row is
 * recomputed from all of the contestant's verdicts on the problem, so duplicates, delivery order and rejudges all end
 * at the same row.
 */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
class ContestScoreRecorderTest extends PostgresTest {

    private static final int AC = 0;
    private static final int WA = -1;
    private static final int CE = -2;
    private static final int SE = 5;

    /** The contest's start; submit times are minutes after it. */
    private static final Instant T = Instant.parse("2026-10-21T02:00:00Z");

    @Autowired ProblemStatsRecorder recorder;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    /** One contestant on one problem of one contest: the key of a {@code t_contest_scores} row. */
    private record Key(UUID contestId, UUID userId, UUID problemId) {

        SubmissionVerdictRecorded verdict(UUID submissionId, int verdict, int minute) {
            return new SubmissionVerdictRecorded(submissionId, problemId, verdict, false, userId, contestId, at(minute));
        }

        SubmissionVerdictRecorded rejudge(UUID submissionId, int verdict, int minute) {
            return new SubmissionVerdictRecorded(submissionId, problemId, verdict, true, userId, contestId, at(minute));
        }
    }

    private static Instant at(int minute) {
        return T.plus(Duration.ofMinutes(minute));
    }

    private UUID problem() {
        return problems.saveAndFlush(Problem.builder().title("score").problemSlug("score-" + UUID.randomUUID())
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build()).getId();
    }

    private UUID contest() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_contests (id, slug, title, starts_at, ends_at, status, created_at, updated_at)
                VALUES (?, ?, 'Scores', ?, ?, 'PUBLISHED', now(), now())""",
                id, "score-" + id, T.atOffset(ZoneOffset.UTC), T.plus(Duration.ofHours(3)).atOffset(ZoneOffset.UTC));
        return id;
    }

    /** A new contestant on a new problem of a new contest. */
    private Key key() {
        return new Key(contest(), UUID.randomUUID(), problem());
    }

    private static ScoreRow score(Key k, int attemptsBeforeAc, Instant acceptedAt) {
        return new ScoreRow(k.userId(), k.problemId(), attemptsBeforeAc, acceptedAt);
    }

    /** The contestant's row on the problem, or null. */
    private ScoreRow row(Key k) {
        List<ScoreRow> rows = jdbc.query("""
                SELECT attempts_before_ac, accepted_at FROM t_contest_scores
                WHERE contest_id = ? AND user_id = ? AND problem_id = ?""", (rs, i) -> {
            OffsetDateTime acceptedAt = rs.getObject("accepted_at", OffsetDateTime.class);
            return score(k, rs.getInt("attempts_before_ac"), acceptedAt == null ? null : acceptedAt.toInstant());
        }, k.contestId(), k.userId(), k.problemId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int scoreRowsOf(UUID problemId) {
        return jdbc.queryForObject("SELECT count(*) FROM t_contest_scores WHERE problem_id = ?", Integer.class, problemId);
    }

    private UUID contestOf(UUID submissionId) {
        return jdbc.queryForObject("SELECT contest_id FROM t_processed_verdicts WHERE submission_id = ?",
                UUID.class, submissionId);
    }

    private Long count(UUID problemId, int verdict) {
        return jdbc.query("SELECT submission_count FROM t_problem_stats WHERE problem_id = ? AND verdict = ?",
                rs -> rs.next() ? rs.getLong(1) : null, problemId, verdict);
    }

    @Test
    void firstAcceptedSetsAcceptedAtOnce() {
        Key k = key();

        recorder.record(k.verdict(UUID.randomUUID(), WA, 5));
        recorder.record(k.verdict(UUID.randomUUID(), AC, 10));
        recorder.record(k.verdict(UUID.randomUUID(), AC, 20));

        assertThat(row(k)).isEqualTo(score(k, 1, at(10)));
    }

    @Test
    void compileAndSystemErrorsDoNotCount() {
        Key k = key();

        recorder.record(k.verdict(UUID.randomUUID(), CE, 1));
        recorder.record(k.verdict(UUID.randomUUID(), SE, 2));
        assertThat(row(k)).isEqualTo(score(k, 0, null));
        recorder.record(k.verdict(UUID.randomUUID(), WA, 3));
        recorder.record(k.verdict(UUID.randomUUID(), AC, 4));

        assertThat(row(k)).isEqualTo(score(k, 1, at(4)));
    }

    @Test
    void attemptsAfterTheFirstAcDoNotCount() {
        Key k = key();

        recorder.record(k.verdict(UUID.randomUUID(), AC, 1));
        recorder.record(k.verdict(UUID.randomUUID(), WA, 2));

        assertThat(row(k)).isEqualTo(score(k, 0, at(1)));
    }

    @Test
    void outOfOrderDeliveryGivesTheSameRow() {
        Key k = key();

        recorder.record(k.verdict(UUID.randomUUID(), AC, 10));
        assertThat(row(k)).isEqualTo(score(k, 0, at(10)));
        recorder.record(k.verdict(UUID.randomUUID(), WA, 5));

        assertThat(row(k)).isEqualTo(score(k, 1, at(10)));
    }

    @Test
    void rejudgeRecomputesTheRow() {
        Key k = key();
        UUID wrong = UUID.randomUUID();
        recorder.record(k.verdict(wrong, WA, 5));
        recorder.record(k.verdict(UUID.randomUUID(), AC, 10));
        assertThat(row(k)).isEqualTo(score(k, 1, at(10)));

        assertThat(recorder.record(k.rejudge(wrong, AC, 5))).isTrue();

        assertThat(row(k)).isEqualTo(score(k, 0, at(5)));
    }

    /** The first AC rejudged to WA: it becomes a wrong try, and the next AC is the accepted one. */
    @Test
    void rejudgeThatTakesTheAcAwayMovesAcceptedAt() {
        Key k = key();
        UUID firstAc = UUID.randomUUID();
        recorder.record(k.verdict(UUID.randomUUID(), WA, 5));
        recorder.record(k.verdict(firstAc, AC, 10));
        recorder.record(k.verdict(UUID.randomUUID(), AC, 20));
        assertThat(row(k)).isEqualTo(score(k, 1, at(10)));

        assertThat(recorder.record(k.rejudge(firstAc, WA, 10))).isTrue();

        assertThat(row(k)).isEqualTo(score(k, 2, at(20)));
    }

    /** The only AC rejudged to a system error (a timed-out rejudge): no AC is left, and the SE is no try. */
    @Test
    void rejudgeToSystemErrorDropsTheAc() {
        Key k = key();
        UUID accepted = UUID.randomUUID();
        recorder.record(k.verdict(UUID.randomUUID(), WA, 5));
        recorder.record(k.verdict(accepted, AC, 10));
        assertThat(row(k)).isEqualTo(score(k, 1, at(10)));

        assertThat(recorder.record(k.rejudge(accepted, SE, 10))).isTrue();

        assertThat(row(k)).isEqualTo(score(k, 1, null));
    }

    /** A verdict recomputes its own contestant's row in its own contest only. */
    @Test
    void aVerdictMovesOnlyItsOwnContestantsRowInItsOwnContest() {
        Key alice = key();
        Key bob = new Key(alice.contestId(), UUID.randomUUID(), alice.problemId());
        Key aliceElsewhere = new Key(contest(), alice.userId(), alice.problemId());
        recorder.record(alice.verdict(UUID.randomUUID(), WA, 5));
        recorder.record(bob.verdict(UUID.randomUUID(), AC, 7));
        recorder.record(aliceElsewhere.verdict(UUID.randomUUID(), AC, 3));

        recorder.record(alice.verdict(UUID.randomUUID(), AC, 12));

        assertThat(row(alice)).isEqualTo(score(alice, 1, at(12)));
        assertThat(row(bob)).isEqualTo(score(bob, 0, at(7)));
        assertThat(row(aliceElsewhere)).isEqualTo(score(aliceElsewhere, 0, at(3)));
    }

    @Test
    void duplicateDeliveryChangesNothing() {
        Key k = key();
        SubmissionVerdictRecorded wrong = k.verdict(UUID.randomUUID(), WA, 5);
        SubmissionVerdictRecorded accepted = k.verdict(UUID.randomUUID(), AC, 10);
        recorder.record(wrong);
        recorder.record(accepted);
        ScoreRow before = row(k);

        assertThat(recorder.record(wrong)).isFalse();
        assertThat(recorder.record(accepted)).isFalse();

        assertThat(row(k)).isEqualTo(before).isEqualTo(score(k, 1, at(10)));
    }

    @Test
    void practiceVerdictTouchesNoScore() {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();

        assertThat(recorder.record(new SubmissionVerdictRecorded(submissionId, problemId, AC, false,
                UUID.randomUUID(), null, at(10)))).isTrue();

        assertThat(scoreRowsOf(problemId)).isZero();
        assertThat(contestOf(submissionId)).isNull();
    }

    @Test
    void unknownContestIsSkipped() {
        Key k = new Key(UUID.randomUUID(), UUID.randomUUID(), problem()); // no such contest
        UUID submissionId = UUID.randomUUID();

        assertThat(recorder.record(k.verdict(submissionId, AC, 10))).isTrue(); // the statistics still count it

        assertThat(scoreRowsOf(k.problemId())).isZero();
        assertThat(contestOf(submissionId)).isNull();
        assertThat(count(k.problemId(), AC)).isEqualTo(1L);
    }

    /** Producers from 0.8.0 on always send both, so a contest verdict without either is a producer bug: a WARN. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aContestVerdictWithoutItsSubmitterOrSubmitTimeIsSkipped(CapturedOutput output) {
        Key k = key();
        UUID noSubmitter = UUID.randomUUID();
        UUID noSubmitTime = UUID.randomUUID();

        recorder.record(new SubmissionVerdictRecorded(noSubmitter, k.problemId(), AC, false, null,
                k.contestId(), at(10)));
        recorder.record(new SubmissionVerdictRecorded(noSubmitTime, k.problemId(), WA, false, k.userId(),
                k.contestId(), null));

        assertThat(scoreRowsOf(k.problemId())).isZero();
        for (UUID submissionId : List.of(noSubmitter, noSubmitTime)) {
            assertThat(output.getOut().lines().filter(line -> line.contains(submissionId.toString())))
                    .anyMatch(line -> line.contains("WARN") && line.contains("keeps no score"));
        }
    }

    /**
     * A producer from before 0.8.0 (submission-service rolled back during a contest) sends verdicts without their
     * contest fields. A rejudge of a submission attached earlier still moves its score: the row's own key is used.
     */
    @Test
    void aRejudgeWithoutContestFieldsStillMovesAnAttachedScore() {
        Key k = key();
        UUID accepted = UUID.randomUUID();
        recorder.record(k.verdict(UUID.randomUUID(), WA, 5));
        recorder.record(k.verdict(accepted, AC, 10));
        assertThat(row(k)).isEqualTo(score(k, 1, at(10)));

        assertThat(recorder.record(new SubmissionVerdictRecorded(accepted, k.problemId(), WA, true))).isTrue();

        assertThat(row(k)).isEqualTo(score(k, 2, null));
    }

    @Test
    void verdictsTheStatisticsAlreadyHoldStillKeepTheScore() {
        Key k = key();
        UUID wrong = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        // Counted without their contest fields (a consumer that kept no scores yet)...
        recorder.record(new SubmissionVerdictRecorded(wrong, k.problemId(), WA));
        recorder.record(new SubmissionVerdictRecorded(accepted, k.problemId(), AC));
        assertThat(row(k)).isNull();

        // ...then the WA delivered again whole ("already counted") and the AC rejudged to AC ("kept its verdict").
        assertThat(recorder.record(k.verdict(wrong, WA, 5))).isFalse();
        assertThat(recorder.record(k.rejudge(accepted, AC, 10))).isFalse();

        assertThat(row(k)).isEqualTo(score(k, 1, at(10)));
    }

    // --- A verdict and another transaction at once. Real commits, so these clean up after themselves.

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aContestDeletedWhileItsVerdictIsRecordedIsSkipped() throws Exception {
        Key k = key();
        UUID submissionId = UUID.randomUUID();
        try {
            // The delete holds the contest's row; the verdict reaches it, waits, and the delete then commits.
            boolean counted = race(() -> jdbc.update("DELETE FROM t_contests WHERE id = ?", k.contestId()),
                    k.verdict(submissionId, AC, 10));

            assertThat(counted).isTrue(); // no exception, so no retry and no dead letter; the statistics still count it
            assertThat(count(k.problemId(), AC)).isEqualTo(1L);
            assertThat(scoreRowsOf(k.problemId())).isZero();
        } finally {
            deleteCommitted(k, submissionId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoVerdictsOfOneContestantRecordedAtOnceBothCount() throws Exception {
        Key k = key();
        UUID wrong = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        try {
            // Two consumers at once (a rebalance). The WA's transaction is held open after its recompute; the AC's
            // must not recompute before the WA commits, or it would read verdicts without the WA and write them over
            // the WA's row.
            race(() -> recorder.record(k.verdict(wrong, WA, 5)), k.verdict(accepted, AC, 10));

            assertThat(row(k)).isEqualTo(score(k, 1, at(10)));
        } finally {
            deleteCommitted(k, wrong, accepted);
        }
    }

    /**
     * Runs {@code earlier} in a transaction held open, then records {@code later} in its own; once that is seen
     * waiting for a lock, {@code earlier} commits, and both must finish.
     *
     * @return what recording {@code later} answered
     */
    private boolean race(Runnable earlier, SubmissionVerdictRecorded later) throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                earlier.run();
                ran.countDown();
                try {
                    commit.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> waiter = threads.submit(() -> recorder.record(later));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'""",
                    Integer.class) > 0);
            commit.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return waiter.get(10, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            threads.shutdownNow();
        }
    }

    private void deleteCommitted(Key k, UUID... submissionIds) {
        for (UUID submissionId : submissionIds) {
            jdbc.update("DELETE FROM t_processed_verdicts WHERE submission_id = ?", submissionId);
        }
        jdbc.update("DELETE FROM t_contest_scores WHERE contest_id = ?", k.contestId());
        jdbc.update("DELETE FROM t_contests WHERE id = ?", k.contestId());
        jdbc.update("DELETE FROM t_problem_stats WHERE problem_id = ?", k.problemId());
        jdbc.update("DELETE FROM t_problems WHERE id = ?", k.problemId());
    }
}
