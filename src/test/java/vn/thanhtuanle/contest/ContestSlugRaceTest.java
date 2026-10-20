package vn.thanhtuanle.contest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two creates of one contest slug at once: both pass existsBySlug (neither has committed), so only ux_contests_slug
 * decides. The loser is refused exactly like a taken slug (ResourceAlreadyExistException, which the contest controller
 * answers with 409), not with a 500. Commits for real, so it cleans up after itself: PostgresTest shares its database.
 */
class ContestSlugRaceTest extends PostgresTest {

    @Autowired ContestService contests;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_contests WHERE slug LIKE 'race-%'"); // its problem rows go by ON DELETE CASCADE
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE 'contest-race-%'");
    }

    /** A competing create has inserted the slug but not committed when ours passes existsBySlug. */
    @Test
    void theLoserOfAConcurrentCreateIsRefusedLikeATakenSlug() throws Exception {
        String problem = "contest-race-" + UUID.randomUUID();
        problems.saveAndFlush(Problem.builder().title("race").problemSlug(problem)
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build());
        String slug = "race-" + UUID.randomUUID().toString().substring(0, 8);
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
}
