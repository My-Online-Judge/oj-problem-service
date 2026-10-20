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
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.contest.dto.ContestDetail;
import vn.thanhtuanle.contest.dto.ContestListResponse;
import vn.thanhtuanle.contest.dto.ContestProblemView;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.contest.dto.ContestSummary;
import vn.thanhtuanle.contest.dto.ProblemProgress;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.support.MutableClock;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Contests over real Postgres: labels, validation, the public list and detail, problems read through a contest, and
 * which changes a started contest refuses.
 */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
class ContestServiceTest extends PostgresTest {

    /** Every contest here starts at T and ends 3 hours later unless a test says otherwise. */
    private static final Instant T = Instant.parse("2026-10-21T02:00:00Z");
    private static final Duration LENGTH = Duration.ofHours(3);

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T);
        }
    }

    @Autowired ContestService contests;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void beforeTheStart() {
        clock.set(T.minus(Duration.ofHours(1)));
    }

    private String problem(ProblemStatus status) {
        String slug = "contest-p-" + UUID.randomUUID();
        problems.saveAndFlush(Problem.builder().title("Title of " + slug).problemSlug(slug)
                .status(status.getValue()).timeLimit(1000).memoryLimit(256L).build());
        return slug;
    }

    private String problem() {
        return problem(ProblemStatus.ACTIVE);
    }

    private UUID problemId(String slug) {
        return jdbc.queryForObject("SELECT id FROM t_problems WHERE problem_slug = ?", UUID.class, slug);
    }

    private static String slug(String kind) {
        return "c-" + kind + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ContestRequest request(String slug, Instant startsAt, Instant endsAt, ContestStatus status,
            List<String> problemSlugs) {
        return new ContestRequest(slug, "Contest " + slug, "About " + slug, startsAt, endsAt, status, problemSlugs);
    }

    /** A PUBLISHED contest from T to T + 3h. */
    private static ContestRequest published(String slug, String... problemSlugs) {
        return request(slug, T, T.plus(LENGTH), ContestStatus.PUBLISHED, List.of(problemSlugs));
    }

    private static ContestRequest withStatus(ContestRequest r, ContestStatus status) {
        return new ContestRequest(r.slug(), r.title(), r.description(), r.startsAt(), r.endsAt(), status, r.problemSlugs());
    }

    private static ContestRequest withStart(ContestRequest r, Instant startsAt) {
        return new ContestRequest(r.slug(), r.title(), r.description(), startsAt, r.endsAt(), r.status(), r.problemSlugs());
    }

    private static ContestRequest withProblems(ContestRequest r, String... problemSlugs) {
        return new ContestRequest(r.slug(), r.title(), r.description(), r.startsAt(), r.endsAt(), r.status(),
                List.of(problemSlugs));
    }

    /** Writes what is pending and forgets every loaded entity, so that what follows reads the database. */
    private void persisted() {
        em.flush();
        em.clear();
    }

    private UUID contestId(String slug) {
        em.flush();
        return jdbc.queryForObject("SELECT id FROM t_contests WHERE slug = ?", UUID.class, slug);
    }

    /** This contest's rows in t_contests, t_contest_problems and t_contest_scores. */
    private List<Integer> rowsOf(UUID contestId) {
        return List.of(
                jdbc.queryForObject("SELECT count(*) FROM t_contests WHERE id = ?", Integer.class, contestId),
                jdbc.queryForObject("SELECT count(*) FROM t_contest_problems WHERE contest_id = ?", Integer.class, contestId),
                jdbc.queryForObject("SELECT count(*) FROM t_contest_scores WHERE contest_id = ?", Integer.class, contestId));
    }

    private void score(UUID contestId, UUID userId, String problemSlug, int attemptsBeforeAc, Instant acceptedAt) {
        jdbc.update("""
                INSERT INTO t_contest_scores (contest_id, user_id, problem_id, attempts_before_ac, accepted_at)
                VALUES (?, ?, ?, ?, ?)""", contestId, userId, problemId(problemSlug), attemptsBeforeAc,
                acceptedAt == null ? null : acceptedAt.atOffset(ZoneOffset.UTC));
    }

    private static List<String> slugs(List<ContestSummary> summaries) {
        return summaries.stream().map(ContestSummary::slug).toList();
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return (ErrorCode) ((AppException) e).getErrorCode();
    }

    // --- creation and validation ---

    @Test
    void createAssignsLabelsByOrder() {
        String p1 = problem(), p2 = problem(), p3 = problem();
        String slug = slug("labels");

        ContestDetail created = contests.create(published(slug, p1, p2, p3), true);

        assertThat(created.problems()).extracting(ContestProblemView::label, ContestProblemView::problemSlug)
                .containsExactly(tuple("A", p1), tuple("B", p2), tuple("C", p3));
        persisted();
        assertThat(jdbc.queryForList("""
                SELECT cp.label, cp.position, p.problem_slug
                FROM t_contest_problems cp JOIN t_contests c ON c.id = cp.contest_id JOIN t_problems p ON p.id = cp.problem_id
                WHERE c.slug = ? ORDER BY cp.position""", slug))
                .extracting(r -> r.get("label"), r -> r.get("position"), r -> r.get("problem_slug"))
                .containsExactly(tuple("A", 0, p1), tuple("B", 1, p2), tuple("C", 2, p3));
    }

    @Test
    void createRejectsUnknownOrDeletedProblems() {
        String live = problem();
        String deleted = problem(ProblemStatus.DELETED);

        assertThatThrownBy(() -> contests.create(published(slug("unknown"), live, "no-such-problem"), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown problem: no-such-problem");
        assertThatThrownBy(() -> contests.create(published(slug("deleted"), live, deleted), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown problem: " + deleted);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_contests", Integer.class)).isZero();
    }

    @Test
    void createRejectsDuplicateProblems() {
        String p1 = problem(), p2 = problem();

        assertThatThrownBy(() -> contests.create(published(slug("dup"), p1, p2, p1), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Duplicate problem: " + p1);
    }

    @Test
    void createRejectsMoreThan26Problems() {
        List<String> many = IntStream.range(0, 27).mapToObj(i -> problem()).toList();

        assertThatThrownBy(() -> contests.create(published(slug("many"), many.toArray(String[]::new)), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A contest has 1 to 26 problems");

        ContestDetail full = contests.create(published(slug("full"), many.subList(0, 26).toArray(String[]::new)), true);
        assertThat(full.problems()).hasSize(26).last().extracting(ContestProblemView::label).isEqualTo("Z");
        persisted(); // the label check constraint accepts Z
    }

    @Test
    void createRejectsEndNotAfterStart() {
        String p = problem();

        assertThatThrownBy(() -> contests.create(request(slug("zero"), T, T, ContestStatus.PUBLISHED, List.of(p)), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A contest must end after it starts");
        assertThatThrownBy(() -> contests.create(request(slug("back"), T, T.minusSeconds(1), ContestStatus.DRAFT, List.of(p)), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A contest must end after it starts");
    }

    @Test
    void createRejectsATakenSlug() {
        String p = problem();
        String slug = slug("taken");
        contests.create(published(slug, p), true);
        persisted();

        assertThatThrownBy(() -> contests.create(published(slug, p), true))
                .isInstanceOf(ResourceAlreadyExistException.class).hasMessage("Contest slug already exists: " + slug);
    }

    // --- listing ---

    @Test
    void listSplitsByPhaseAndHidesDraftsFromNonEditors() {
        clock.set(T);
        String p1 = problem(), p2 = problem();
        Duration hour = Duration.ofHours(1);
        String runningLate = slug("running-late");
        String runningSoon = slug("running-soon");
        String runningDraft = slug("running-draft");
        String upcomingLate = slug("upcoming-late");
        String upcomingSoon = slug("upcoming-soon");
        String pastOld = slug("past-old");
        String pastNew = slug("past-new");
        String endedNow = slug("ended-now");
        contests.create(request(runningLate, T.minus(hour), T.plus(hour.multipliedBy(3)), ContestStatus.PUBLISHED, List.of(p1)), true);
        contests.create(request(runningSoon, T, T.plus(hour), ContestStatus.PUBLISHED, List.of(p1, p2)), true);
        contests.create(request(runningDraft, T.minus(hour), T.plus(hour.multipliedBy(2)), ContestStatus.DRAFT, List.of(p1)), true);
        contests.create(request(upcomingLate, T.plus(hour.multipliedBy(48)), T.plus(hour.multipliedBy(50)), ContestStatus.PUBLISHED, List.of(p1)), true);
        contests.create(request(upcomingSoon, T.plus(hour.multipliedBy(24)), T.plus(hour.multipliedBy(26)), ContestStatus.PUBLISHED, List.of(p1)), true);
        contests.create(request(pastOld, T.minus(hour.multipliedBy(50)), T.minus(hour.multipliedBy(48)), ContestStatus.PUBLISHED, List.of(p1)), true);
        contests.create(request(pastNew, T.minus(hour.multipliedBy(26)), T.minus(hour.multipliedBy(24)), ContestStatus.PUBLISHED, List.of(p1)), true);
        contests.create(request(endedNow, T.minus(hour), T, ContestStatus.PUBLISHED, List.of(p1)), true);
        persisted();

        ContestListResponse anyone = contests.list(false);

        assertThat(slugs(anyone.running())).containsExactly(runningSoon, runningLate);
        assertThat(slugs(anyone.upcoming())).containsExactly(upcomingSoon, upcomingLate);
        assertThat(slugs(anyone.past())).containsExactly(endedNow, pastNew, pastOld);
        assertThat(anyone.serverTime()).isEqualTo(T);
        ContestSummary soon = anyone.running().get(0);
        assertThat(soon.phase()).isEqualTo(ContestPhase.RUNNING);
        assertThat(soon.status()).isEqualTo(ContestStatus.PUBLISHED);
        assertThat(soon.problemCount()).isEqualTo(2);
        assertThat(soon.startsAt()).isEqualTo(T);
        assertThat(soon.endsAt()).isEqualTo(T.plus(hour));
        assertThat(anyone.upcoming().get(0).phase()).isEqualTo(ContestPhase.UPCOMING);
        assertThat(anyone.past().get(0).phase()).isEqualTo(ContestPhase.ENDED);

        ContestListResponse editor = contests.list(true);

        assertThat(slugs(editor.running())).containsExactly(runningSoon, runningDraft, runningLate);
        assertThat(editor.running().get(1).status()).isEqualTo(ContestStatus.DRAFT);
    }

    @Test
    void pastIsCappedAt50NewestFirst() {
        clock.set(T);
        String p = problem();
        List<String> ended = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            String slug = slug("past-" + i);
            Instant start = T.minus(Duration.ofDays(10)).plus(Duration.ofHours(i));
            contests.create(request(slug, start, start.plus(Duration.ofMinutes(30)), ContestStatus.PUBLISHED, List.of(p)), true);
            ended.add(slug);
        }
        persisted();

        List<ContestSummary> past = contests.list(false).past();

        assertThat(past).hasSize(50);
        assertThat(past).extracting(ContestSummary::endsAt).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(past.get(0).slug()).isEqualTo(ended.get(50));
        assertThat(past.get(49).slug()).isEqualTo(ended.get(1));
    }

    // --- detail ---

    @Test
    void detailHidesProblemsBeforeStart() {
        String p1 = problem(), p2 = problem();
        String slug = slug("hidden");
        contests.create(published(slug, p1, p2), true);
        persisted();
        clock.set(T.minusNanos(1000));

        ContestDetail anyone = contests.detail(slug, false, false, Optional.empty());
        ContestDetail editor = contests.detail(slug, true, true, Optional.empty());

        assertThat(anyone.phase()).isEqualTo(ContestPhase.UPCOMING);
        assertThat(anyone.problems()).isEmpty();
        assertThat(editor.problems()).extracting(ContestProblemView::label, ContestProblemView::problemSlug,
                ContestProblemView::title)
                .containsExactly(tuple("A", p1, "Title of " + p1), tuple("B", p2, "Title of " + p2));

        clock.set(T);
        assertThat(contests.detail(slug, false, false, Optional.empty()).problems()).hasSize(2);
    }

    /** SP12: INACTIVE problems are for problem editors. Seeing a contest's problems early takes problem:update too. */
    @Test
    void aContestEditorWithoutProblemUpdateSeesProblemsOnlyOnceStarted() {
        String p = problem(ProblemStatus.INACTIVE);
        String slug = slug("early");
        String draft = slug("early-draft");
        contests.create(published(slug, p), true);
        contests.create(withStatus(published(draft, p), ContestStatus.DRAFT), true);
        persisted();
        clock.set(T.minusSeconds(1));

        assertThat(contests.detail(slug, true, false, Optional.empty()).problems()).isEmpty();
        assertThat(contests.detail(slug, true, true, Optional.empty()).problems()).hasSize(1);
        assertThat(contests.update(slug, withStatus(published(null, p), ContestStatus.PUBLISHED), false).problems())
                .isEmpty();

        clock.set(T);
        ContestDetail draftForContestEditor = contests.detail(draft, true, false, Optional.empty());
        assertThat(draftForContestEditor.status()).isEqualTo(ContestStatus.DRAFT);
        assertThat(draftForContestEditor.problems()).isEmpty();
        assertThat(contests.detail(draft, true, true, Optional.empty()).problems()).hasSize(1);
        assertThat(contests.detail(slug, true, false, Optional.empty()).problems()).hasSize(1); // started: public rule
    }

    /** Attaching an INACTIVE problem publishes it at the start, so it needs problem:update. */
    @Test
    void attachingAnInactiveProblemNeedsProblemUpdate() {
        String active = problem();
        String inactive = problem(ProblemStatus.INACTIVE);

        assertThatThrownBy(() -> contests.create(published(slug("no-right"), active, inactive), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown problem: " + inactive);
        assertThat(contests.create(published(slug("active-only"), active), false).problems()).isEmpty();

        String slug = slug("with-right");
        ContestRequest original = published(slug, active, inactive);
        contests.create(original, true);
        persisted();
        assertThatThrownBy(() -> contests.update(slug, withProblems(original, inactive, active), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown problem: " + inactive);
        // an unchanged list is not checked again
        assertThat(contests.update(slug, withStatus(original, ContestStatus.DRAFT), false).status())
                .isEqualTo(ContestStatus.DRAFT);
    }

    /** Editors keep them, so an edit can send the problem list back unchanged. */
    @Test
    void theDetailLeavesDeletedProblemsOutForNonEditors() {
        String p1 = problem(), p2 = problem();
        String slug = slug("deleted-shown");
        contests.create(published(slug, p1, p2), true);
        persisted();
        jdbc.update("UPDATE t_problems SET status = ? WHERE problem_slug = ?", ProblemStatus.DELETED.getValue(), p2);
        clock.set(T);

        assertThat(contests.detail(slug, false, false, Optional.empty()).problems())
                .extracting(ContestProblemView::label, ContestProblemView::problemSlug).containsExactly(tuple("A", p1));
        assertThat(contests.detail(slug, true, false, Optional.empty()).problems())
                .extracting(ContestProblemView::label, ContestProblemView::problemSlug)
                .containsExactly(tuple("A", p1), tuple("B", p2));
        assertThat(contests.detail(slug, true, true, Optional.empty()).problems()).hasSize(2);
    }

    @Test
    void draftDetailIs404ForNonEditors() {
        String slug = slug("draft");
        contests.create(withStatus(published(slug, problem()), ContestStatus.DRAFT), true);
        persisted();
        clock.set(T.plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> contests.detail(slug, false, false, Optional.empty()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(contests.detail(slug, true, true, Optional.empty()).status()).isEqualTo(ContestStatus.DRAFT);
        assertThatThrownBy(() -> contests.detail(slug("no-such"), true, true, Optional.empty()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void detailCarriesMyProgress() {
        String p1 = problem(), p2 = problem(), p3 = problem();
        String slug = slug("progress");
        contests.create(published(slug, p1, p2, p3), true);
        UUID contestId = contestId(slug);
        UUID me = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        score(contestId, me, p1, 1, T.plus(Duration.ofMinutes(10)));  // A: solved after 1 WA
        score(contestId, me, p2, 2, null);                             // B: 2 WA
        score(contestId, someoneElse, p3, 0, T.plus(Duration.ofMinutes(5)));
        persisted();
        clock.set(T.plus(Duration.ofMinutes(30)));

        ContestDetail mine = contests.detail(slug, false, false, Optional.of(me));

        assertThat(mine.myProgress()).isEqualTo(Map.of(
                "A", new ProblemProgress(true, 2),
                "B", new ProblemProgress(false, 2)));
        assertThat(contests.detail(slug, false, false, Optional.empty()).myProgress()).isEmpty();
    }

    @Test
    void detailCarriesServerTime() {
        String slug = slug("server-time");
        contests.create(published(slug, problem()), true);
        persisted();
        Instant now = Instant.parse("2026-10-21T01:23:45.678Z");
        clock.set(now);

        assertThat(contests.detail(slug, false, false, Optional.empty()).serverTime()).isEqualTo(now);
    }

    // --- problems read through a contest ---

    @Test
    void problemReadableWhileRunningEvenIfInactive() {
        String inactive = problem(ProblemStatus.INACTIVE);
        String slug = slug("running");
        contests.create(published(slug, inactive), true);
        persisted();
        clock.set(T);

        ProblemResponseDto read = contests.problem(slug, "A", false);

        assertThat(read.getProblemSlug()).isEqualTo(inactive);
        assertThat(read.getStatus()).isEqualTo(ProblemStatus.INACTIVE.getValue());
    }

    @Test
    void problemHiddenBeforeStart() {
        String p = problem();
        String slug = slug("before");
        String draft = slug("draft-running");
        contests.create(published(slug, p), true);
        contests.create(withStatus(published(draft, p), ContestStatus.DRAFT), true);
        persisted();
        clock.set(T.minusNanos(1000));

        assertThatThrownBy(() -> contests.problem(slug, "A", false)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(contests.problem(slug, "A", true).getProblemSlug()).isEqualTo(p); // editors, at any time

        clock.set(T);
        assertThatThrownBy(() -> contests.problem(draft, "A", false)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void problemReadableAfterEnd() {
        String p = problem();
        String slug = slug("after");
        contests.create(published(slug, p), true);
        persisted();
        clock.set(T.plus(LENGTH));

        assertThat(contests.problem(slug, "A", false).getProblemSlug()).isEqualTo(p);
        assertThatThrownBy(() -> contests.problem(slug, "B", false)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> contests.problem(slug, "AB", false)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDeletedProblemIsNotReadableThroughAContest() {
        String p = problem();
        String slug = slug("deleted-later");
        contests.create(published(slug, p), true);
        persisted();
        jdbc.update("UPDATE t_problems SET status = ? WHERE problem_slug = ?", ProblemStatus.DELETED.getValue(), p);
        clock.set(T);

        assertThatThrownBy(() -> contests.problem(slug, "A", true)).isInstanceOf(ResourceNotFoundException.class);
    }

    // --- changes ---

    @Test
    void updateAfterStartMayChangeTitleAndEndOnly() {
        String p1 = problem(), p2 = problem();
        String slug = slug("started");
        ContestRequest original = published(slug, p1);
        contests.create(original, true);
        persisted();
        clock.set(T);

        ContestRequest allowed = new ContestRequest(null, "New title", "New description", T, T.plus(Duration.ofHours(4)),
                ContestStatus.PUBLISHED, List.of(p1));
        ContestDetail updated = contests.update(slug, allowed, true);

        assertThat(updated.title()).isEqualTo("New title");
        persisted();
        ContestDetail read = contests.detail(slug, false, false, Optional.empty());
        assertThat(read.title()).isEqualTo("New title");
        assertThat(read.description()).isEqualTo("New description");
        assertThat(read.endsAt()).isEqualTo(T.plus(Duration.ofHours(4)));

        assertThatThrownBy(() -> contests.update(slug, withStart(allowed, T.plus(Duration.ofMinutes(1))), true))
                .isInstanceOf(AppException.class).extracting(ContestServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.CONTEST_STARTED);
        assertThatThrownBy(() -> contests.update(slug, withProblems(allowed, p1, p2), true))
                .isInstanceOf(AppException.class).extracting(ContestServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.CONTEST_STARTED);
        assertThatThrownBy(() -> contests.update(slug, withProblems(allowed, p2), true))
                .isInstanceOf(AppException.class).extracting(ContestServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.CONTEST_STARTED);
        persisted();
        assertThat(contests.detail(slug, false, false, Optional.empty()).problems())
                .extracting(ContestProblemView::problemSlug).containsExactly(p1);

        // The status may still change, and a DRAFT is no longer locked.
        assertThat(contests.update(slug, withStatus(allowed, ContestStatus.DRAFT), true).status()).isEqualTo(ContestStatus.DRAFT);
        persisted();
        assertThat(contests.update(slug, withProblems(withStatus(allowed, ContestStatus.DRAFT), p2), true).problems())
                .extracting(ContestProblemView::problemSlug).containsExactly(p2);
    }

    /**
     * Clients round differently (Postgres keeps µs, JS ms, datetime-local minutes), so times are stored at whole
     * seconds and the started lock compares seconds: echoing either the sent or the stored start is no change.
     */
    @Test
    void subSecondTimesAreStoredAtWholeSecondsAndAnEchoedStartIsNoChange() {
        String p = problem();
        String slug = slug("sub-second");
        Instant sentStart = T.plusNanos(123_456_789);
        ContestRequest sent = request(slug, sentStart, T.plus(LENGTH).plusMillis(999), ContestStatus.PUBLISHED, List.of(p));

        ContestDetail created = contests.create(sent, true);

        assertThat(created.startsAt()).isEqualTo(T);
        assertThat(created.endsAt()).isEqualTo(T.plus(LENGTH));
        persisted();
        assertThat(jdbc.queryForObject("SELECT starts_at FROM t_contests WHERE slug = ?", OffsetDateTime.class,
                slug).toInstant()).isEqualTo(T);
        clock.set(T.plus(Duration.ofHours(1)));

        ContestRequest retitled = new ContestRequest(null, "Retitled", null, sentStart, sent.endsAt(),
                ContestStatus.PUBLISHED, List.of(p));
        assertThat(contests.update(slug, retitled, true).title()).isEqualTo("Retitled");
        persisted();
        assertThat(contests.update(slug, withStart(retitled, created.startsAt()), true).startsAt()).isEqualTo(T);
        persisted();
        assertThatThrownBy(() -> contests.update(slug, withStart(retitled, T.plusSeconds(1)), true))
                .isInstanceOf(AppException.class).extracting(ContestServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.CONTEST_STARTED);

        // a window that is empty at whole seconds is refused like any other, not by the database
        assertThatThrownBy(() -> contests.create(request(slug("empty"), T.plusMillis(100), T.plusMillis(900),
                ContestStatus.DRAFT, List.of(p)), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A contest must end after it starts");
    }

    @Test
    void updateBeforeStartRewritesProblemsAndStart() {
        String p1 = problem(), p2 = problem(), p3 = problem();
        String slug = slug("reorder");
        contests.create(published(slug, p1, p2), true);
        persisted();

        contests.update(slug, withStart(withProblems(published(null, p1), p2, p1, p3), T.plus(Duration.ofMinutes(30))), true);
        persisted();

        ContestDetail read = contests.detail(slug, true, true, Optional.empty());
        assertThat(read.problems()).extracting(ContestProblemView::label, ContestProblemView::problemSlug)
                .containsExactly(tuple("A", p2), tuple("B", p1), tuple("C", p3));
        assertThat(read.startsAt()).isEqualTo(T.plus(Duration.ofMinutes(30)));
    }

    /** The list is not checked again when it does not change, so a problem deleted since blocks no other change. */
    @Test
    void aProblemDeletedSinceDoesNotBlockOtherChanges() {
        String p = problem();
        String slug = slug("deleted-since");
        ContestRequest original = published(slug, p);
        contests.create(original, true);
        persisted();
        jdbc.update("UPDATE t_problems SET status = ? WHERE problem_slug = ?", ProblemStatus.DELETED.getValue(), p);
        clock.set(T.plus(Duration.ofHours(1)));

        assertThat(contests.update(slug, withStatus(original, ContestStatus.DRAFT), true).status()).isEqualTo(ContestStatus.DRAFT);
    }

    @Test
    void deleteRefusedOnceStarted() {
        String slug = slug("no-delete");
        contests.create(published(slug, problem()), true);
        persisted();
        clock.set(T);

        assertThatThrownBy(() -> contests.delete(slug))
                .isInstanceOf(AppException.class).extracting(ContestServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.CONTEST_STARTED);
        persisted();
        assertThat(rowsOf(contestId(slug))).containsExactly(1, 1, 0);
    }

    @Test
    void deleteBeforeStartRemovesProblems() {
        String p1 = problem(), p2 = problem();
        String slug = slug("delete");
        contests.create(published(slug, p1, p2), true);
        UUID id = contestId(slug);
        persisted();
        clock.set(T.minusNanos(1000));

        contests.delete(slug);
        persisted();

        assertThat(rowsOf(id)).containsExactly(0, 0, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_problems WHERE problem_slug IN (?, ?)", Integer.class, p1, p2))
                .isEqualTo(2);
        assertThatThrownBy(() -> contests.delete(slug)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aStartedContestSetToDraftCanBeDeleted() {
        String p = problem();
        String slug = slug("draft-delete");
        ContestRequest original = published(slug, p);
        contests.create(original, true);
        UUID id = contestId(slug);
        score(id, UUID.randomUUID(), p, 1, T.plus(Duration.ofMinutes(3)));
        persisted();
        assertThat(rowsOf(id)).containsExactly(1, 1, 1);
        clock.set(T.plus(Duration.ofHours(1)));

        contests.update(slug, withStatus(original, ContestStatus.DRAFT), true);
        persisted();
        contests.delete(slug);
        persisted();

        assertThat(rowsOf(id)).containsExactly(0, 0, 0);
    }
}
