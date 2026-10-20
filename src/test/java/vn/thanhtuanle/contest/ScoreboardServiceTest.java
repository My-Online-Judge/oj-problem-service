package vn.thanhtuanle.contest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.contest.dto.ProblemCell;
import vn.thanhtuanle.contest.dto.ScoreboardResponse;
import vn.thanhtuanle.contest.dto.ScoreboardRow;
import vn.thanhtuanle.contest.dto.ScoreboardUser;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.identity.PublicUser;
import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.support.MutableClock;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The scoreboard over real Postgres: who sees it when, usernames from identity, and which score rows count. */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
class ScoreboardServiceTest extends PostgresTest {

    /** Every contest here starts at T and ends 3 hours later. */
    private static final Instant T = Instant.parse("2026-10-21T02:00:00Z");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T);
        }
    }

    @Autowired ScoreboardService scoreboards;
    @Autowired ContestService contests;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @PersistenceContext EntityManager em;

    @MockitoBean PublicUserClient users;

    @BeforeEach
    void beforeTheStart() {
        clock.set(T.minus(Duration.ofHours(1)));
    }

    private String problem() {
        String slug = "board-p-" + UUID.randomUUID();
        problems.saveAndFlush(Problem.builder().title("Title of " + slug).problemSlug(slug)
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build());
        return slug;
    }

    private UUID problemId(String slug) {
        return jdbc.queryForObject("SELECT id FROM t_problems WHERE problem_slug = ?", UUID.class, slug);
    }

    private static ContestRequest request(String slug, ContestStatus status, List<String> problemSlugs) {
        return new ContestRequest(slug, "Contest " + slug, null, T, T.plus(Duration.ofHours(3)), status, problemSlugs);
    }

    /** A contest from T to T + 3h, created before its start; returns its slug. */
    private String contest(ContestStatus status, String... problemSlugs) {
        String slug = "c-board-" + UUID.randomUUID().toString().substring(0, 8);
        contests.create(request(slug, status, List.of(problemSlugs)), true);
        em.flush();
        return slug;
    }

    private UUID contestId(String slug) {
        return jdbc.queryForObject("SELECT id FROM t_contests WHERE slug = ?", UUID.class, slug);
    }

    private void score(String contest, UUID userId, String problemSlug, int attemptsBeforeAc, Instant acceptedAt) {
        jdbc.update("""
                INSERT INTO t_contest_scores (contest_id, user_id, problem_id, attempts_before_ac, accepted_at)
                VALUES (?, ?, ?, ?, ?)""", contestId(contest), userId, problemId(problemSlug), attemptsBeforeAc,
                acceptedAt == null ? null : acceptedAt.atOffset(ZoneOffset.UTC));
    }

    private static PublicUser profile(UUID id, String username) {
        return new PublicUser(id, username, "Name of " + username, null, null);
    }

    private void running() {
        em.clear();
        clock.set(T.plus(Duration.ofHours(1)));
    }

    @Test
    void usernamesComeFromIdentity() {
        String p1 = problem(), p2 = problem();
        String slug = contest(ContestStatus.PUBLISHED, p1, p2);
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        score(slug, alice, p1, 1, T.plus(Duration.ofMinutes(15)));
        score(slug, bob, p1, 0, T.plus(Duration.ofMinutes(5)));
        score(slug, bob, p2, 2, null);
        when(users.byIds(anyCollection())).thenReturn(Map.of(alice, profile(alice, "alice"), bob, profile(bob, "bob")));
        running();

        ScoreboardResponse board = scoreboards.scoreboard(slug, false);

        assertThat(board.labels()).containsExactly("A", "B");
        assertThat(board.rows()).extracting(ScoreboardRow::rank, ScoreboardRow::user, ScoreboardRow::solved,
                        ScoreboardRow::penalty)
                .containsExactly(
                        tuple(1, new ScoreboardUser(bob, "bob", "Name of bob"), 1, 5L),
                        tuple(2, new ScoreboardUser(alice, "alice", "Name of alice"), 1, 35L));
        assertThat(board.rows().get(0).problems()).isEqualTo(Map.of(
                "A", new ProblemCell(true, 1, 5L),
                "B", new ProblemCell(false, 2, null)));
        verify(users).byIds(Set.of(alice, bob));
    }

    @Test
    void identityDownLeavesUsernamesNull() {
        String p = problem();
        String slug = contest(ContestStatus.PUBLISHED, p);
        UUID alice = UUID.randomUUID();
        score(slug, alice, p, 0, T.plus(Duration.ofMinutes(7)));
        when(users.byIds(anyCollection())).thenReturn(Map.of()); // what PublicUserClient answers when identity fails
        running();

        ScoreboardResponse board = scoreboards.scoreboard(slug, false);

        assertThat(board.rows()).extracting(ScoreboardRow::user, ScoreboardRow::solved, ScoreboardRow::penalty)
                .containsExactly(tuple(new ScoreboardUser(alice, null, null), 1, 7L));
    }

    @Test
    void upcomingContestHasNoRowsForNonEditors() {
        String p1 = problem(), p2 = problem();
        String slug = contest(ContestStatus.PUBLISHED, p1, p2);
        UUID tester = UUID.randomUUID();
        // Not reachable through the API (scores lock the start, and the judge takes no submission before it), so
        // inserted by hand to pin the rule.
        score(slug, tester, p1, 0, T.minus(Duration.ofMinutes(30)));
        em.clear();

        ScoreboardResponse anyone = scoreboards.scoreboard(slug, false);

        assertThat(anyone.labels()).containsExactly("A", "B");
        assertThat(anyone.rows()).isEmpty();
        verifyNoInteractions(users);
        assertThat(scoreboards.scoreboard(slug, true).rows()).extracting(r -> r.user().id()).containsExactly(tester);
    }

    @Test
    void draftIs404ForNonEditors() {
        String p = problem();
        String slug = contest(ContestStatus.DRAFT, p);
        UUID alice = UUID.randomUUID();
        score(slug, alice, p, 1, null);
        running();

        assertThatThrownBy(() -> scoreboards.scoreboard(slug, false)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(scoreboards.scoreboard(slug, true).rows()).extracting(r -> r.user().id()).containsExactly(alice);
        assertThatThrownBy(() -> scoreboards.scoreboard("c-no-such-contest", true))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Score rows of a problem removed while the contest was a DRAFT stay in t_contest_scores; the board is built from
     * the contest's current problems only, so such a row neither shows nor counts.
     */
    @Test
    void aRemovedProblemsRowNeitherShowsNorCounts() {
        String kept = problem(), removed = problem();
        String slug = contest(ContestStatus.DRAFT, removed, kept);
        UUID removedOnly = UUID.randomUUID(), both = UUID.randomUUID();
        score(slug, removedOnly, removed, 0, T.plus(Duration.ofMinutes(1)));
        score(slug, both, removed, 1, T.plus(Duration.ofMinutes(2)));
        score(slug, both, kept, 0, T.plus(Duration.ofMinutes(10)));
        contests.update(slug, request(null, ContestStatus.PUBLISHED, List.of(kept)), true);
        em.flush();
        running();

        ScoreboardResponse board = scoreboards.scoreboard(slug, false);

        assertThat(board.labels()).containsExactly("A");
        assertThat(board.rows()).extracting(r -> r.user().id(), ScoreboardRow::solved, ScoreboardRow::penalty,
                        ScoreboardRow::problems)
                .containsExactly(tuple(both, 1, 10L, Map.of("A", new ProblemCell(true, 1, 10L))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_contest_scores WHERE contest_id = ?", Integer.class,
                contestId(slug))).isEqualTo(3);
    }

    /**
     * Task 12 keeps a (0 attempts, no AC) row for a contestant whose contest submissions were only compile or system
     * errors. The brief leaves out users with nothing counted, so such a contestant is not listed.
     */
    @Test
    void aContestantWithOnlyCompileOrSystemErrorsIsLeftOut() {
        String p = problem();
        String slug = contest(ContestStatus.PUBLISHED, p);
        UUID onlyErrors = UUID.randomUUID(), tried = UUID.randomUUID();
        score(slug, onlyErrors, p, 0, null);
        score(slug, tried, p, 1, null);
        when(users.byIds(anyCollection())).thenReturn(Map.of());
        running();

        ScoreboardResponse board = scoreboards.scoreboard(slug, false);

        assertThat(board.rows()).extracting(r -> r.user().id(), ScoreboardRow::rank, ScoreboardRow::solved,
                        ScoreboardRow::penalty)
                .containsExactly(tuple(tried, 1, 0, 0L));
    }
}
