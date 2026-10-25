package vn.thanhtuanle.contest.enrollment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.contest.ContestEnrollment;
import vn.thanhtuanle.contest.ContestRepository;
import vn.thanhtuanle.contest.ContestService;
import vn.thanhtuanle.contest.ContestStatus;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.contest.dto.EnrollmentRow;
import vn.thanhtuanle.contest.dto.EnrollmentState;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.oj.common.identity.PublicUser;
import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.support.MutableClock;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/** Registration over real Postgres: the rules table of the contest enrollment design §4.2. */
@Transactional // each test rolls back: PostgresTest shares one database with ProblemSchemaTest, which expects no rows
class EnrollmentServiceTest extends PostgresTest {

    /** Every contest here starts at T and ends 3 hours later. */
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

    @Autowired EnrollmentService enrollments;
    @Autowired ContestService contests;
    @Autowired ContestRepository contestRepository;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoBean PublicUserClient users;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @BeforeEach
    void beforeTheStart() {
        clock.set(T.minus(Duration.ofHours(1)));
        Map<UUID, PublicUser> known = Map.of(
                alice, new PublicUser(alice, "alice", "Alice Tran", null, null),
                bob, new PublicUser(bob, "bob", "Bob Le", null, null),
                carol, new PublicUser(carol, "carol", "Carol Pham", null, null));
        when(users.byIds(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return ids.stream().filter(known::containsKey).collect(Collectors.toMap(id -> id, known::get));
        });
    }

    private String problem() {
        String slug = "enroll-p-" + UUID.randomUUID();
        problems.saveAndFlush(Problem.builder().title("Title of " + slug).problemSlug(slug)
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build());
        return slug;
    }

    private static ContestRequest request(String slug, ContestStatus status, ContestEnrollment kind, String... problemSlugs) {
        return new ContestRequest(slug, "Contest " + slug, null, T, T.plus(LENGTH), status, List.of(problemSlugs), kind);
    }

    /** A contest from T to T + 3h, created before its start; returns its slug. */
    private String contest(ContestStatus status, ContestEnrollment kind) {
        String slug = "c-enroll-" + UUID.randomUUID().toString().substring(0, 8);
        contests.create(request(slug, status, kind, problem()), true);
        return slug;
    }

    private String required() {
        return contest(ContestStatus.PUBLISHED, ContestEnrollment.REQUIRED);
    }

    private UUID contestId(String slug) {
        return contestRepository.findBySlug(slug).orElseThrow().getId();
    }

    private static ErrorCode codeOf(Throwable e) {
        return (ErrorCode) ((AppException) e).getErrorCode();
    }

    @Test
    void aUserRegistersOnceAndSeesTheCount() {
        String slug = required();

        EnrollmentState first = enrollments.enroll(slug, alice, false);
        EnrollmentState again = enrollments.enroll(slug, alice, false);
        EnrollmentState other = enrollments.enroll(slug, bob, false);

        assertThat(first).isEqualTo(new EnrollmentState(true, 1));
        assertThat(again).isEqualTo(new EnrollmentState(true, 1));
        assertThat(other).isEqualTo(new EnrollmentState(true, 2));
        assertThat(enrollments.isEnrolled(contestId(slug), alice)).isTrue();
    }

    @Test
    void anOpenContestRefusesRegistration() {
        String slug = contest(ContestStatus.PUBLISHED, ContestEnrollment.OPEN);

        assertThatThrownBy(() -> enrollments.enroll(slug, alice, false))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.ENROLLMENT_NOT_REQUIRED));
    }

    @Test
    void registrationStaysOpenWhileRunningAndClosesAtTheEnd() {
        String slug = required();

        clock.set(T.plusSeconds(1));
        assertThat(enrollments.enroll(slug, alice, false).enrolled()).isTrue();

        clock.set(T.plus(LENGTH));
        assertThatThrownBy(() -> enrollments.enroll(slug, bob, false))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.ENROLLMENT_CLOSED));
    }

    @Test
    void aDraftRefusesRegistrationEvenForAnEditor() {
        String slug = contest(ContestStatus.DRAFT, ContestEnrollment.REQUIRED);

        assertThatThrownBy(() -> enrollments.enroll(slug, alice, true))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.ENROLLMENT_CLOSED));
        assertThatThrownBy(() -> enrollments.enroll(slug, alice, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void withdrawWorksOnlyBeforeTheStart() {
        String slug = required();
        enrollments.enroll(slug, alice, false);

        assertThat(enrollments.withdraw(slug, alice, false)).isEqualTo(new EnrollmentState(false, 0));
        assertThat(enrollments.withdraw(slug, alice, false)).isEqualTo(new EnrollmentState(false, 0));

        enrollments.enroll(slug, alice, false);
        clock.set(T.plusSeconds(1));
        assertThatThrownBy(() -> enrollments.withdraw(slug, alice, false))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.ENROLLMENT_CLOSED));
        assertThat(enrollments.isEnrolled(contestId(slug), alice)).isTrue();
    }

    @Test
    void anAdminRemovesAParticipantAtAnyTime() {
        String slug = required();
        enrollments.enroll(slug, alice, false);
        clock.set(T.plusSeconds(1));

        enrollments.remove(slug, alice);

        assertThat(enrollments.isEnrolled(contestId(slug), alice)).isFalse();
        assertThatCode(() -> enrollments.remove(slug, alice)).doesNotThrowAnyException();
    }

    @Test
    void aRemovedUserCannotSubmitButMayRegisterAgainWhileOpen() {
        String slug = required();
        enrollments.enroll(slug, alice, false);
        clock.set(T.plusSeconds(1));
        enrollments.remove(slug, alice);

        // What the gRPC check asks (Task 6): not enrolled → the submit is refused.
        assertThat(enrollments.isEnrolled(contestId(slug), alice)).isFalse();
        assertThat(enrollments.enroll(slug, alice, false)).isEqualTo(new EnrollmentState(true, 1));
    }

    @Test
    void switchingToOpenKeepsTheList() {
        String slug = required();
        enrollments.enroll(slug, alice, false);
        ContestRequest open = new ContestRequest(null, "Contest " + slug, null, T, T.plus(LENGTH), ContestStatus.PUBLISHED,
                null, ContestEnrollment.OPEN);
        contests.update(slug, open, true);

        assertThat(enrollments.participants(slug, 0, 20, null).getTotalElements()).isEqualTo(1);
        clock.set(T.plusSeconds(1));
        assertThat(enrollments.maySeeProblems(contestRepository.findBySlug(slug).orElseThrow(),
                Optional.of(UUID.randomUUID()), false)).isTrue();
    }

    @Test
    void participantsArePagedNewestFirstAndSearchable() {
        String slug = required();
        clock.set(T.minus(Duration.ofHours(3)));
        enrollments.enroll(slug, alice, false);
        clock.set(T.minus(Duration.ofHours(2)));
        enrollments.enroll(slug, bob, false);
        clock.set(T.minus(Duration.ofHours(1)));
        enrollments.enroll(slug, carol, false);

        PageResponse<EnrollmentRow> firstPage = enrollments.participants(slug, 0, 2, null);
        assertThat(firstPage.getData()).extracting(r -> r.user().username()).containsExactly("carol", "bob");
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getData().get(0).enrolledAt()).isEqualTo(T.minus(Duration.ofHours(1)));

        PageResponse<EnrollmentRow> found = enrollments.participants(slug, 0, 20, "ALI");
        assertThat(found.getData()).extracting(r -> r.user().username()).containsExactly("alice");
        assertThat(found.getTotalElements()).isEqualTo(1);
    }

    @Test
    void participantsAreListedEvenWhenIdentityIsDown() {
        String slug = required();
        enrollments.enroll(slug, alice, false);
        doReturn(Map.of()).when(users).byIds(any());   // doReturn: when() would run the @BeforeEach answer on a null arg

        PageResponse<EnrollmentRow> page = enrollments.participants(slug, 0, 20, null);

        assertThat(page.getData()).singleElement().satisfies(r -> {
            assertThat(r.user().id()).isEqualTo(alice);
            assertThat(r.user().username()).isNull();
        });
    }

    @Test
    void maySeeProblemsFollowsTheRulesTable() {
        String slug = required();
        var contest = contestRepository.findBySlug(slug).orElseThrow();
        enrollments.enroll(slug, alice, false);
        UUID stranger = UUID.randomUUID();

        clock.set(T.plusSeconds(1));   // running
        assertThat(enrollments.maySeeProblems(contest, Optional.of(alice), false)).isTrue();
        assertThat(enrollments.maySeeProblems(contest, Optional.of(stranger), false)).isFalse();
        assertThat(enrollments.maySeeProblems(contest, Optional.empty(), false)).isFalse();
        assertThat(enrollments.maySeeProblems(contest, Optional.empty(), true)).isTrue();

        clock.set(T.plus(LENGTH));     // ended: an archive for everyone
        assertThat(enrollments.maySeeProblems(contest, Optional.of(stranger), false)).isTrue();
        assertThat(enrollments.maySeeProblems(contest, Optional.empty(), false)).isTrue();
    }
}
