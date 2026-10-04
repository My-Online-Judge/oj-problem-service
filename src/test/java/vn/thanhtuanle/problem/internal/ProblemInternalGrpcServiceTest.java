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
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenClientInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetSampleTestCasesRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.oj.common.grpc.problem.v1.SampleTestCase;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundleException;
import vn.thanhtuanle.testcase.TestCaseBundleStore;
import vn.thanhtuanle.testcase.TestCaseSourceStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** The internal API through the real server: Spring gRPC wiring, the global service-token interceptor, mapping. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureInProcessTransport
class ProblemInternalGrpcServiceTest {

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        InMemoryTestCaseSourceStore inMemorySources() {
            return new InMemoryTestCaseSourceStore();
        }
    }

    @Autowired GrpcChannelFactory channels;
    @Autowired ProblemRepository problems;
    @Autowired TestCaseSourceStore sources;
    @MockitoBean TestCaseBundleStore bundleStore;
    @Value("${oj.rpc.token}") String token;

    private ManagedChannel channel;
    private ProblemInternalGrpc.ProblemInternalBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = channels.createChannel("problem-service");
        stub = ProblemInternalGrpc.newBlockingStub(channel).withInterceptors(new ServiceTokenClientInterceptor(token));
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private Problem problem(ProblemStatus status) {
        String slug = "grpc-" + UUID.randomUUID();
        Problem p = Problem.builder().title("grpc").problemSlug(slug).status(status.getValue())
                .timeLimit(1500).memoryLimit(128L).testCases(new ArrayList<>()).build();
        addCase(p, slug, 1, true, "1 2\n", "3\n");
        addCase(p, slug, 2, false, "secret in\n", "secret out\n");
        return problems.save(p);
    }

    private void addCase(Problem p, String slug, int n, boolean sample, String in, String out) {
        p.getTestCases().add(TestCase.builder().input(slug + "/" + n + ".in").output(slug + "/" + n + ".out")
                .sample(sample).problem(p).build());
        sources.put(slug + "/" + n + ".in", in.getBytes(StandardCharsets.UTF_8));
        sources.put(slug + "/" + n + ".out", out.getBytes(StandardCharsets.UTF_8));
    }

    private static Status.Code codeOf(Runnable call) {
        try {
            call.run();
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    @Test
    void theJudgeSpecCarriesTheLimitsAndTheCurrentBundle() {
        Problem p = problem(ProblemStatus.INACTIVE);   // inactive problems stay submittable
        when(bundleStore.findCurrentVersion(p.getProblemSlug())).thenReturn(Optional.of("f7837fd99ca5"));

        JudgeSpec spec = stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(p.getProblemSlug()).build());

        assertThat(spec.getProblemId()).isEqualTo(p.getId().toString());
        assertThat(spec.getProblemSlug()).isEqualTo(p.getProblemSlug());
        assertThat(spec.getTimeLimitMs()).isEqualTo(1500);
        assertThat(spec.getMemoryLimitMb()).isEqualTo(128L);
        assertThat(spec.getTestCaseVersion()).isEqualTo("f7837fd99ca5");
    }

    @Test
    void anUnknownOrDeletedSlugIsNotFound() {
        Problem deleted = problem(ProblemStatus.DELETED);
        when(bundleStore.findCurrentVersion(anyString())).thenReturn(Optional.of("abc"));

        assertThat(codeOf(() -> stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug("no-such").build())))
                .isEqualTo(Status.Code.NOT_FOUND);
        assertThat(codeOf(() -> stub.getJudgeSpec(
                GetJudgeSpecRequest.newBuilder().setProblemSlug(deleted.getProblemSlug()).build())))
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void aProblemWithoutABundleFailsItsPrecondition() {
        Problem p = problem(ProblemStatus.ACTIVE);
        when(bundleStore.findCurrentVersion(p.getProblemSlug())).thenReturn(Optional.empty());

        assertThat(codeOf(() -> stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(p.getProblemSlug()).build())))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void storageThatCannotBeReadIsUnavailable() {
        Problem p = problem(ProblemStatus.ACTIVE);
        when(bundleStore.findCurrentVersion(p.getProblemSlug())).thenThrow(new TestCaseBundleException("minio down"));

        assertThat(codeOf(() -> stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(p.getProblemSlug()).build())))
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void onlySampleCasesLeaveTheService_deletedProblemsIncluded() {
        Problem p = problem(ProblemStatus.DELETED);

        var cases = stub.getSampleTestCases(GetSampleTestCasesRequest.newBuilder()
                .setProblemId(p.getId().toString()).build()).getCasesList();

        assertThat(cases).extracting(SampleTestCase::getName, SampleTestCase::getInput, SampleTestCase::getExpectedOutput)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "1 2\n", "3\n"));
    }

    @Test
    void anUnknownProblemHasNoSamplesAndAMalformedIdIsInvalid() {
        assertThat(stub.getSampleTestCases(GetSampleTestCasesRequest.newBuilder()
                .setProblemId(UUID.randomUUID().toString()).build()).getCasesList()).isEmpty();
        assertThat(codeOf(() -> stub.getSampleTestCases(GetSampleTestCasesRequest.newBuilder()
                .setProblemId("not-a-uuid").build()))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void aCallWithoutTheServiceTokenIsUnauthenticated() {
        var anonymous = ProblemInternalGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> anonymous.getSampleTestCases(GetSampleTestCasesRequest.newBuilder()
                .setProblemId(UUID.randomUUID().toString()).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
