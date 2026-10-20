package vn.thanhtuanle.contest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Contest writes that race. Two creates of one slug: only ux_contests_slug decides, and the loser is refused exactly
 * like a taken slug (ResourceAlreadyExistException, which the contest controller answers with 409), not with a 500.
 * Two changes of one contest: the second waits for the first to commit and then decides on what it committed.
 * Commits for real, so it cleans up after itself: PostgresTest shares its database.
 */
class ContestConcurrencyTest extends PostgresTest {

    @Autowired ContestService contests;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_contests WHERE slug LIKE 'race-%'"); // its problem rows go by ON DELETE CASCADE
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE 'contest-race-%'");
    }

    private String problem() {
        String slug = "contest-race-" + UUID.randomUUID();
        problems.saveAndFlush(Problem.builder().title("race").problemSlug(slug)
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build());
        return slug;
    }

    private static String slug() {
        return "race-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ContestRequest request(String slug, Instant start, ContestStatus status, String problem) {
        return new ContestRequest(slug, "Contest " + slug, null, start, start.plus(Duration.ofHours(3)), status,
                List.of(problem));
    }

    /** A DRAFT contest that started an hour ago, holding {@code problem}. */
    private Instant startedDraft(String slug, String problem) {
        Instant start = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        contests.create(request(slug, start, ContestStatus.DRAFT, problem), true);
        return start;
    }

    /**
     * Another admin applies {@code publish} to {@code slug} in a transaction held open for 2 s. Once that update has
     * returned (inside the open transaction), runs {@code change} and returns how long it took, in nanoseconds.
     */
    private long whileAPublishIsOpen(String slug, ContestRequest publish, Runnable change) throws Exception {
        CountDownLatch updated = new CountDownLatch(1);
        Thread other = new Thread(() -> new TransactionTemplate(tm).executeWithoutResult(tx -> {
            contests.update(slug, publish, true);
            updated.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        other.start();
        assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        try {
            change.run();
            return System.nanoTime() - started;
        } finally {
            other.join();
        }
    }

    private static void assertContestStarted(Throwable refused) {
        assertThat(refused).isInstanceOf(AppException.class);
        assertThat(((AppException) refused).getErrorCode()).isEqualTo(ErrorCode.CONTEST_STARTED);
    }

    /** A competing create has inserted the slug but not committed when ours passes existsBySlug. */
    @Test
    void theLoserOfAConcurrentCreateIsRefusedLikeATakenSlug() throws Exception {
        String problem = problem();
        String slug = slug();
        Instant start = Instant.now().plus(Duration.ofDays(1));
        CountDownLatch inserted = new CountDownLatch(1);
        Thread winner = new Thread(() -> new TransactionTemplate(tm).executeWithoutResult(tx -> {
            jdbc.update("""
                    INSERT INTO t_contests (id, slug, title, starts_at, ends_at, status, created_at, updated_at)
                    VALUES (?, ?, 'winner', now() + interval '1 day', now() + interval '2 days', 'DRAFT', now(), now())""",
                    UUID.randomUUID(), slug);
            inserted.countDown();
            try {
                Thread.sleep(2000); // the loser must block on the unique index meanwhile
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        winner.start();
        assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> contests.create(new ContestRequest(slug, "loser", null, start,
                start.plus(Duration.ofHours(3)), ContestStatus.PUBLISHED, List.of(problem)), true))
                .isInstanceOf(ResourceAlreadyExistException.class)
                .hasMessage("Contest slug already exists: " + slug);
        winner.join();

        assertThat(System.nanoTime() - started).as("blocked until the winner committed")
                .isGreaterThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(jdbc.queryForObject("SELECT title FROM t_contests WHERE slug = ?", String.class, slug))
                .isEqualTo("winner");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM t_contest_problems cp JOIN t_contests c ON c.id = cp.contest_id
                WHERE c.slug = ?""", Integer.class, slug)).isZero();
    }

    /**
     * An edit loaded while the contest was a DRAFT swaps its problems while another admin publishes it. Unserialised,
     * the edit read the DRAFT, so the lock did not apply, and a started PUBLISHED contest ended up with new problems.
     */
    @Test
    void anEditWaitsForAConcurrentPublishAndThenSeesTheContestStarted() throws Exception {
        String p1 = problem(), p2 = problem();
        String slug = slug();
        Instant start = startedDraft(slug, p1);

        Throwable[] refused = new Throwable[1];
        long took = whileAPublishIsOpen(slug, request(null, start, ContestStatus.PUBLISHED, p1), () -> refused[0] =
                catchThrowable(() -> contests.update(slug, request(null, start, ContestStatus.DRAFT, p2), true)));

        assertThat(took).as("waited for the publish to commit").isGreaterThan(TimeUnit.SECONDS.toNanos(1));
        assertContestStarted(refused[0]);
        assertThat(jdbc.queryForObject("SELECT status FROM t_contests WHERE slug = ?", String.class, slug))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForList("""
                SELECT p.problem_slug FROM t_contest_problems cp JOIN t_contests c ON c.id = cp.contest_id
                JOIN t_problems p ON p.id = cp.problem_id WHERE c.slug = ?""", String.class, slug))
                .containsExactly(p1);
    }

    @Test
    void aDeleteWaitsForAConcurrentPublishAndIsThenRefused() throws Exception {
        String p = problem();
        String slug = slug();
        Instant start = startedDraft(slug, p);

        Throwable[] refused = new Throwable[1];
        long took = whileAPublishIsOpen(slug, request(null, start, ContestStatus.PUBLISHED, p),
                () -> refused[0] = catchThrowable(() -> contests.delete(slug)));

        assertThat(took).as("waited for the publish to commit").isGreaterThan(TimeUnit.SECONDS.toNanos(1));
        assertContestStarted(refused[0]);
        assertThat(jdbc.queryForObject("SELECT status FROM t_contests WHERE slug = ?", String.class, slug))
                .isEqualTo("PUBLISHED");
    }
}
