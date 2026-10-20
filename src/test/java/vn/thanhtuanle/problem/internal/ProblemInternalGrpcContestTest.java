package vn.thanhtuanle.problem.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.test.AutoConfigureInProcessTransport;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.contest.ContestRepository;
import vn.thanhtuanle.contest.ContestService;
import vn.thanhtuanle.contest.ContestStatus;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.grpc.ProblemRpcReasons;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenClientInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.support.MutableClock;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * GetJudgeSpec for a contest submission, through the real server: a spec only while a PUBLISHED contest that contains
 * the problem runs ([startsAt, endsAt)), INACTIVE problems included, with the contest's id; otherwise
 * FAILED_PRECONDITION with the {@code oj-reason: CONTEST_NOT_RUNNING} trailer. H2: no test here reaches
 * {@code t_contest_scores}, which has no entity.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureInProcessTransport
class ProblemInternalGrpcContestTest {

    /** Every contest here runs from T to T + 3h. */
    private static final Instant T = Instant.parse("2026-10-21T02:00:00Z");
    private static final Instant END = T.plus(Duration.ofHours(3));

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T);
        }
    }

    @Autowired GrpcChannelFactory channels;
    @Autowired ProblemRepository problems;
    @Autowired ContestService contestService;
    @Autowired ContestRepository contests;
    @Autowired MutableClock clock;
    @MockitoBean TestCaseBundleStore bundleStore;
    @Value("${oj.rpc.token}") String token;

    private ManagedChannel channel;
    private ProblemInternalGrpc.ProblemInternalBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = channels.createChannel("problem-service");
        stub = ProblemInternalGrpc.newBlockingStub(channel).withInterceptors(new ServiceTokenClientInterceptor(token));
        when(bundleStore.findCurrentVersion(anyString())).thenReturn(Optional.of("f7837fd99ca5"));
        clock.set(T.plus(Duration.ofHours(1)));
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private String problem(ProblemStatus status) {
        String slug = "grpc-contest-p-" + UUID.randomUUID();
        problems.save(Problem.builder().title("Title of " + slug).problemSlug(slug).status(status.getValue())
                .timeLimit(1500).memoryLimit(128L).build());
        return slug;
    }

    /** A contest from T to T + 3h holding these problems. */
    private String contest(ContestStatus status, String... problemSlugs) {
        String slug = "c-grpc-" + UUID.randomUUID().toString().substring(0, 8);
        contestService.create(new ContestRequest(slug, "Contest " + slug, null, T, END, status, List.of(problemSlugs)), true);
        return slug;
    }

    private UUID contestId(String slug) {
        return contests.findBySlug(slug).orElseThrow().getId();
    }

    private JudgeSpec spec(String problemSlug, String contestSlug) {
        return stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(problemSlug)
                .setContestSlug(contestSlug).build());
    }

    private StatusRuntimeException refusal(String problemSlug, String contestSlug) {
        return catchThrowableOfType(StatusRuntimeException.class, () -> spec(problemSlug, contestSlug));
    }

    private static void assertNotRunning(StatusRuntimeException e) {
        assertThat(e).as("the call must be refused").isNotNull();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(e.getStatus().getDescription()).isEqualTo("Contest is not running");
        assertThat(ProblemRpcReasons.isContestNotRunning(e)).isTrue();
    }

    @Test
    void runningContestAnswersTheSpecWithItsId() {
        String p = problem(ProblemStatus.INACTIVE);
        String c = contest(ContestStatus.PUBLISHED, p);

        JudgeSpec spec = spec(p, c);

        assertThat(spec.getProblemSlug()).isEqualTo(p);
        assertThat(spec.getTimeLimitMs()).isEqualTo(1500);
        assertThat(spec.getTestCaseVersion()).isEqualTo("f7837fd99ca5");
        assertThat(spec.getContestId()).isEqualTo(contestId(c).toString());
    }

    @Test
    void upcomingContestIsRefusedWithTheReason() {
        String p = problem(ProblemStatus.ACTIVE);
        String c = contest(ContestStatus.PUBLISHED, p);
        clock.set(T.minusSeconds(1));

        assertNotRunning(refusal(p, c));
    }

    @Test
    void endedContestIsRefused() {
        String p = problem(ProblemStatus.ACTIVE);
        String c = contest(ContestStatus.PUBLISHED, p);
        clock.set(END.plus(Duration.ofMinutes(5)));

        assertNotRunning(refusal(p, c));
    }

    @Test
    void atEndsAtExactlyIsRefused() {
        String p = problem(ProblemStatus.ACTIVE);
        String c = contest(ContestStatus.PUBLISHED, p);
        clock.set(END);

        assertNotRunning(refusal(p, c));
    }

    @Test
    void atStartsAtExactlyIsAccepted() {
        String p = problem(ProblemStatus.INACTIVE);
        String c = contest(ContestStatus.PUBLISHED, p);
        clock.set(T);

        assertThat(spec(p, c).getContestId()).isEqualTo(contestId(c).toString());
    }

    @Test
    void draftContestIsRefused() {
        String p = problem(ProblemStatus.INACTIVE);
        String c = contest(ContestStatus.DRAFT, p);

        assertNotRunning(refusal(p, c));
    }

    @Test
    void problemNotInTheContestIsRefused() {
        String inContest = problem(ProblemStatus.ACTIVE);
        String other = problem(ProblemStatus.ACTIVE);
        String c = contest(ContestStatus.PUBLISHED, inContest);

        assertNotRunning(refusal(other, c));
        assertNotRunning(refusal(inContest, "c-no-such-contest"));
        // the same answer whether or not the problem exists: no hint about hidden slugs
        assertNotRunning(refusal("grpc-no-such-problem", c));
    }

    @Test
    void noContestSlugBehavesAsBefore() {
        String active = problem(ProblemStatus.ACTIVE);
        String inactive = problem(ProblemStatus.INACTIVE);
        contest(ContestStatus.PUBLISHED, active, inactive);

        assertThat(spec(active, "").getContestId()).isEmpty();
        // a running contest does not open its INACTIVE problem to a submission that does not name it
        assertThat(refusal(inactive, "").getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(inactive).setAllowInactive(true)
                .build()).getContestId()).isEmpty();
    }

    @Test
    void otherFailuresInARunningContestAreUnchanged() {
        String noBundle = problem(ProblemStatus.ACTIVE);
        String deleted = problem(ProblemStatus.ACTIVE);
        String c = contest(ContestStatus.PUBLISHED, noBundle, deleted);
        when(bundleStore.findCurrentVersion(noBundle)).thenReturn(Optional.empty());
        Problem gone = problems.findLiveBySlug(deleted).orElseThrow();
        gone.setStatus(ProblemStatus.DELETED.getValue());
        problems.save(gone);

        StatusRuntimeException withoutBundle = refusal(noBundle, c);
        assertThat(withoutBundle.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(ProblemRpcReasons.isContestNotRunning(withoutBundle)).isFalse();
        assertThat(refusal(deleted, c).getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
}
