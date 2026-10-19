package vn.thanhtuanle.problem;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Counting a verdict: once per submission, only for problems this service knows. A rejudge moves the submission's
 * count from the verdict it was counted under ({@code last_status}) to the new one.
 */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
@ExtendWith(OutputCaptureExtension.class)
class ProblemStatsRecorderTest extends PostgresTest {

    private static final int AC = 0;
    private static final int WA = -1;
    private static final int TLE = 1;

    @Autowired ProblemStatsRecorder recorder;
    @Autowired ProblemRepository problems;
    @Autowired ProblemStatRepository stats;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager em;

    private UUID problem() {
        return problems.saveAndFlush(Problem.builder().title("rec").problemSlug("rec-" + UUID.randomUUID())
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build()).getId();
    }

    private static SubmissionVerdictRecorded first(UUID submissionId, UUID problemId, int verdict) {
        return new SubmissionVerdictRecorded(submissionId, problemId, verdict);
    }

    private static SubmissionVerdictRecorded rejudge(UUID submissionId, UUID problemId, int verdict) {
        return new SubmissionVerdictRecorded(submissionId, problemId, verdict, true);
    }

    private Long count(UUID problemId, int verdict) {
        return jdbc.query("SELECT submission_count FROM t_problem_stats WHERE problem_id = ? AND verdict = ?",
                rs -> rs.next() ? rs.getLong(1) : null, problemId, verdict);
    }

    /** The recorder's entity changes reach the database at commit, which a rolled-back test never does. */
    private void flush() {
        if (TestTransaction.isActive()) {
            em.flush();
        }
    }

    private Integer lastStatus(UUID submissionId) {
        flush();
        return jdbc.queryForObject("SELECT last_status FROM t_processed_verdicts WHERE submission_id = ?",
                Integer.class, submissionId);
    }

    private LocalDateTime processedAt(UUID submissionId) {
        flush();
        return jdbc.queryForObject("SELECT processed_at FROM t_processed_verdicts WHERE submission_id = ?",
                LocalDateTime.class, submissionId);
    }

    @Test
    void theSameSubmissionDeliveredTwiceCountsOnce() {
        UUID problemId = problem();
        var event = new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0);

        assertThat(recorder.record(event)).isTrue();
        assertThat(recorder.record(event)).isFalse();

        assertThat(count(problemId, 0)).isEqualTo(1L);
    }

    @Test
    void submissionsAddUpPerVerdict() {
        UUID problemId = problem();
        recorder.record(new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0));
        recorder.record(new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0));
        recorder.record(new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, -1));

        assertThat(count(problemId, 0)).isEqualTo(2L);
        assertThat(count(problemId, -1)).isEqualTo(1L);
    }

    @Test
    void aVerdictForAnUnknownProblemIsSkippedAndNotMarkedProcessed() {
        UUID submissionId = UUID.randomUUID();

        assertThat(recorder.record(new SubmissionVerdictRecorded(submissionId, UUID.randomUUID(), 0))).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_processed_verdicts WHERE submission_id = ?",
                Integer.class, submissionId)).isZero();
    }

    @Test
    void aFirstVerdictRecordsItsStatus() {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();

        assertThat(recorder.record(first(submissionId, problemId, WA))).isTrue();

        assertThat(lastStatus(submissionId)).isEqualTo(WA);
        assertThat(count(problemId, WA)).isEqualTo(1L);
    }

    @Test
    void aRejudgeMovesTheCountFromTheOldVerdictToTheNew() {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();
        recorder.record(first(submissionId, problemId, AC));

        assertThat(recorder.record(rejudge(submissionId, problemId, WA))).isTrue();

        assertThat(count(problemId, AC)).isZero();
        assertThat(count(problemId, WA)).isEqualTo(1L);
        assertThat(lastStatus(submissionId)).isEqualTo(WA);
    }

    @Test
    void aRejudgeWithTheSameVerdictChangesNothing() {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();
        recorder.record(first(submissionId, problemId, AC));
        LocalDateTime longAgo = LocalDateTime.of(2020, 1, 1, 0, 0);
        jdbc.update("UPDATE t_processed_verdicts SET processed_at = ? WHERE submission_id = ?", longAgo, submissionId);

        assertThat(recorder.record(rejudge(submissionId, problemId, AC))).isFalse();

        assertThat(count(problemId, AC)).isEqualTo(1L);
        assertThat(lastStatus(submissionId)).isEqualTo(AC);
        assertThat(processedAt(submissionId)).isAfter(longAgo);
    }

    @Test
    void aRedeliveredRejudgeChangesNothing() {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();
        recorder.record(first(submissionId, problemId, AC));
        recorder.record(rejudge(submissionId, problemId, WA));

        assertThat(recorder.record(rejudge(submissionId, problemId, WA))).isFalse();

        assertThat(count(problemId, AC)).isZero();
        assertThat(count(problemId, WA)).isEqualTo(1L);
        assertThat(lastStatus(submissionId)).isEqualTo(WA);
    }

    @Test
    void twoRejudgesInARowEndAtTheLastVerdict() {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();
        recorder.record(first(submissionId, problemId, AC));

        assertThat(recorder.record(rejudge(submissionId, problemId, WA))).isTrue();
        assertThat(recorder.record(rejudge(submissionId, problemId, TLE))).isTrue();

        assertThat(count(problemId, AC)).isZero();
        assertThat(count(problemId, WA)).isZero();
        assertThat(count(problemId, TLE)).isEqualTo(1L);
        assertThat(lastStatus(submissionId)).isEqualTo(TLE);
    }

    @Test
    void aRejudgeOfANeverCountedSubmissionCountsItOnce() {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();

        assertThat(recorder.record(rejudge(submissionId, problemId, WA))).isTrue();
        assertThat(recorder.record(rejudge(submissionId, problemId, WA))).isFalse();
        // The original verdict arriving late (a dead letter replayed) is a duplicate by now.
        assertThat(recorder.record(first(submissionId, problemId, AC))).isFalse();

        assertThat(count(problemId, WA)).isEqualTo(1L);
        assertThat(count(problemId, AC)).isNull();
        assertThat(lastStatus(submissionId)).isEqualTo(WA);
    }

    @Test
    void aRejudgeWhosePreviousVerdictIsUnknownLeavesTheCountsAlone(CapturedOutput output) {
        UUID problemId = problem();
        UUID submissionId = UUID.randomUUID();
        // Counted as AC before V5 and missed by the backfill: the row says nothing about the verdict.
        jdbc.update("INSERT INTO t_processed_verdicts (submission_id, processed_at, last_status) VALUES (?, now(), NULL)",
                submissionId);
        jdbc.update("INSERT INTO t_problem_stats (problem_id, verdict, submission_count) VALUES (?, ?, 1)", problemId, AC);

        assertThat(recorder.record(rejudge(submissionId, problemId, WA))).isFalse();
        // A second rejudge must not move a count the first one never added (WA is 0: the move would make it two).
        assertThat(recorder.record(rejudge(submissionId, problemId, TLE))).isFalse();

        assertThat(count(problemId, AC)).isEqualTo(1L);
        assertThat(count(problemId, WA)).isNull();
        assertThat(count(problemId, TLE)).isNull();
        assertThat(lastStatus(submissionId)).isNull();
        assertThat(output).contains("previous verdict unknown");
    }

    @Test
    void decrementNeverGoesBelowZero() {
        UUID problemId = problem();
        jdbc.update("INSERT INTO t_problem_stats (problem_id, verdict, submission_count) VALUES (?, ?, 0)", problemId, WA);

        stats.decrement(problemId, WA);
        stats.decrement(problemId, AC); // no row at all

        assertThat(count(problemId, WA)).isZero();
        assertThat(count(problemId, AC)).isNull();
    }

    // --- Two events of one submission in two transactions at once (the listener runs them back to back, but a
    // --- rebalance or a second consumer could overlap them). Real commits, so these clean up after themselves.

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRejudgeRacingTheFirstVerdictStillMovesTheCount() throws Exception {
        UUID problemId = problems.save(Problem.builder().title("race").problemSlug("race-" + UUID.randomUUID())
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build()).getId();
        UUID submissionId = UUID.randomUUID();
        try {
            // The rejudge finds no row to lock; its insert waits for the first verdict's, which then commits.
            race(first(submissionId, problemId, AC), rejudge(submissionId, problemId, WA));

            assertThat(count(problemId, AC)).isZero();
            assertThat(count(problemId, WA)).isEqualTo(1L);
            assertThat(lastStatus(submissionId)).isEqualTo(WA);
        } finally {
            deleteCommitted(problemId, submissionId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoRacingRejudgesTakeTurnsOnTheRow() throws Exception {
        UUID problemId = problems.save(Problem.builder().title("race").problemSlug("race-" + UUID.randomUUID())
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build()).getId();
        UUID submissionId = UUID.randomUUID();
        try {
            recorder.record(first(submissionId, problemId, AC));

            // The second rejudge must read WA as the previous verdict, not the AC it saw before the first committed.
            race(rejudge(submissionId, problemId, WA), rejudge(submissionId, problemId, TLE));

            assertThat(count(problemId, AC)).isZero();
            assertThat(count(problemId, WA)).isZero();
            assertThat(count(problemId, TLE)).isEqualTo(1L);
            assertThat(lastStatus(submissionId)).isEqualTo(TLE);
        } finally {
            deleteCommitted(problemId, submissionId);
        }
    }

    /**
     * Records {@code earlier} in a transaction held open, then {@code later} in its own; once {@code later} is seen
     * waiting for a lock, {@code earlier} commits and both must finish.
     */
    private void race(SubmissionVerdictRecorded earlier, SubmissionVerdictRecorded later) throws Exception {
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                recorder.record(earlier);
                recorded.countDown();
                try {
                    commit.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(recorded.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> waiter = threads.submit(() -> recorder.record(later));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'""",
                    Integer.class) > 0);
            commit.countDown();
            holder.get(10, TimeUnit.SECONDS);
            waiter.get(10, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            threads.shutdownNow();
        }
    }

    private void deleteCommitted(UUID problemId, UUID submissionId) {
        jdbc.update("DELETE FROM t_processed_verdicts WHERE submission_id = ?", submissionId);
        jdbc.update("DELETE FROM t_problem_stats WHERE problem_id = ?", problemId);
        jdbc.update("DELETE FROM t_problems WHERE id = ?", problemId);
    }
}
