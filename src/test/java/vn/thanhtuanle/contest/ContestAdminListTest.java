package vn.thanhtuanle.contest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.contest.dto.ContestSummary;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.support.MutableClock;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** The contest managers' list: every contest, DRAFT included, newest start first, paged, with a literal search. */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
class ContestAdminListTest extends PostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired ContestService contests;
    @Autowired ContestRepository contestRows;
    @Autowired ProblemRepository problems;
    @Autowired MutableClock clock;

    @BeforeEach
    void atNow() {
        clock.set(NOW);
    }

    private Contest save(String slug, String title, Instant startsAt, ContestStatus status) {
        return contestRows.saveAndFlush(Contest.builder().slug(slug).title(title).startsAt(startsAt)
                .endsAt(startsAt.plus(Duration.ofHours(3))).status(status).build());
    }

    private Contest save(String slug, Instant startsAt, ContestStatus status) {
        return save(slug, "Contest " + slug, startsAt, status);
    }

    private static List<String> slugs(PageResponse<ContestSummary> page) {
        return page.getData().stream().map(ContestSummary::slug).toList();
    }

    @Test
    void everyContestIsListedWithNoFiftyCap() {
        for (int i = 0; i < 55; i++) {
            // past and future, DRAFT and PUBLISHED
            Instant start = NOW.plus(Duration.ofDays(i - 27));
            save("cap-%02d".formatted(i), start, i % 2 == 0 ? ContestStatus.DRAFT : ContestStatus.PUBLISHED);
        }

        PageResponse<ContestSummary> page = contests.listAll(0, 20, null);

        assertThat(page.getData()).hasSize(20);
        assertThat(page.getTotalElements()).isEqualTo(55);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(contests.listAll(2, 20, null).getData()).hasSize(15);
    }

    @Test
    void newestStartFirstThenSlug() {
        save("old-one", NOW.minus(Duration.ofDays(3)), ContestStatus.PUBLISHED);
        save("tie-b", NOW.plus(Duration.ofDays(1)), ContestStatus.PUBLISHED);
        save("tie-a", NOW.plus(Duration.ofDays(1)), ContestStatus.DRAFT);
        save("far-future", NOW.plus(Duration.ofDays(30)), ContestStatus.PUBLISHED);
        save("running", NOW.minus(Duration.ofHours(1)), ContestStatus.PUBLISHED);

        assertThat(slugs(contests.listAll(0, 20, null)))
                .containsExactly("far-future", "tie-a", "tie-b", "running", "old-one");
    }

    @Test
    void draftsAreIncluded() {
        save("a-draft", NOW.plus(Duration.ofDays(1)), ContestStatus.DRAFT);
        save("a-live", NOW.plus(Duration.ofDays(2)), ContestStatus.PUBLISHED);

        PageResponse<ContestSummary> page = contests.listAll(0, 20, null);

        assertThat(page.getData()).extracting(ContestSummary::slug, ContestSummary::status)
                .containsExactly(tuple("a-live", ContestStatus.PUBLISHED), tuple("a-draft", ContestStatus.DRAFT));
        assertThat(page.getData()).extracting(ContestSummary::phase)
                .containsExactly(ContestPhase.UPCOMING, ContestPhase.UPCOMING);
    }

    @Test
    void qMatchesSlugOrTitleIgnoringCase() {
        save("spring-cup", "Cuộc thi mùa xuân", NOW.plusSeconds(10), ContestStatus.PUBLISHED);
        save("autumn", "The SPRING finals", NOW.plusSeconds(20), ContestStatus.DRAFT);
        save("winter", "Winter", NOW.plusSeconds(30), ContestStatus.PUBLISHED);

        assertThat(slugs(contests.listAll(0, 20, "Spring"))).containsExactly("autumn", "spring-cup");
        assertThat(slugs(contests.listAll(0, 20, "  SPRING-CUP  "))).containsExactly("spring-cup");
        assertThat(slugs(contests.listAll(0, 20, "MÙA xuân"))).containsExactly("spring-cup");
        assertThat(slugs(contests.listAll(0, 20, "   "))).hasSize(3);
        assertThat(slugs(contests.listAll(0, 20, ""))).hasSize(3);
        assertThat(contests.listAll(0, 20, "nothing").getTotalElements()).isZero();
    }

    @Test
    void aPercentInQIsLiteral() {
        save("half-off", "50% off", NOW.plusSeconds(10), ContestStatus.PUBLISHED);
        save("other", "Other", NOW.plusSeconds(20), ContestStatus.PUBLISHED);
        save("under_score", "Under", NOW.plusSeconds(30), ContestStatus.PUBLISHED);
        save("underXscore", "Under", NOW.plusSeconds(40), ContestStatus.PUBLISHED);
        save("back-slash", "a\\b", NOW.plusSeconds(50), ContestStatus.PUBLISHED);

        assertThat(slugs(contests.listAll(0, 20, "%"))).containsExactly("half-off");
        assertThat(slugs(contests.listAll(0, 20, "0% o"))).containsExactly("half-off");
        assertThat(slugs(contests.listAll(0, 20, "r_s"))).containsExactly("under_score");
        assertThat(slugs(contests.listAll(0, 20, "\\"))).containsExactly("back-slash");
    }

    @Test
    void sizeIsCappedAt100() {
        for (int i = 0; i < 105; i++) {
            save("many-%03d".formatted(i), NOW.plus(Duration.ofMinutes(i)), ContestStatus.PUBLISHED);
        }

        PageResponse<ContestSummary> page = contests.listAll(0, 500, null);

        assertThat(page.getData()).hasSize(100);
        assertThat(page.getSize()).isEqualTo(100);
        assertThat(page.getTotalElements()).isEqualTo(105);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void aNegativePageIsRefused() {
        assertThatThrownBy(() -> contests.listAll(-1, 20, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void problemCountsAreFilled() {
        Contest two = save("two-problems", NOW.plusSeconds(10), ContestStatus.PUBLISHED);
        save("no-problems", NOW.plusSeconds(20), ContestStatus.DRAFT);
        Problem live = problem(ProblemStatus.ACTIVE);
        Problem inactive = problem(ProblemStatus.INACTIVE);
        Problem deleted = problem(ProblemStatus.DELETED);
        List<Problem> chosen = new ArrayList<>(List.of(live, inactive, deleted));
        for (int i = 0; i < chosen.size(); i++) {
            two.getProblems().add(new ContestProblem(two, chosen.get(i), (char) ('A' + i), i));
        }
        contestRows.saveAndFlush(two);

        assertThat(contests.listAll(0, 20, null).getData())
                .extracting(ContestSummary::slug, ContestSummary::problemCount)
                .containsExactly(tuple("no-problems", 0), tuple("two-problems", 2)); // the deleted one does not count
    }

    private Problem problem(ProblemStatus status) {
        String slug = "adm-p-" + UUID.randomUUID();
        return problems.saveAndFlush(Problem.builder().title("Title of " + slug).problemSlug(slug)
                .status(status.getValue()).timeLimit(1000).memoryLimit(256L).build());
    }
}
