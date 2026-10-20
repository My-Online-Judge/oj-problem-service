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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenClientInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.testcase.InMemoryTestCaseSourceStore;
import vn.thanhtuanle.testcase.TestCaseBundleException;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * GetJudgeSpec for a problem judged by a checker, through the real server: the spec carries the checker's source and
 * version (qduoj's spj fields, Task 15), a plain problem's carries neither, and a missing source refuses the spec.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureInProcessTransport
class ProblemInternalGrpcCheckerTest {

    private static final String CHECKER = """
            #include <fstream>
            int main(int argc, char** argv) {
                std::ifstream in(argv[1]), out(argv[2]);
                long long a, b, sum;
                in >> a >> b;
                return (out >> sum) && sum == a + b ? 0 : 1;
            }
            """;

    /** Records, for every checker read, whether a transaction was open at that moment; can be made unreachable. */
    static class RecordingSources extends InMemoryTestCaseSourceStore {
        final List<Boolean> transactionOpenOnCheckerRead = new CopyOnWriteArrayList<>();
        volatile boolean unreachable;

        @Override
        public Optional<byte[]> get(String path) {
            if (path.endsWith("/spj.cpp")) {
                transactionOpenOnCheckerRead.add(TransactionSynchronizationManager.isActualTransactionActive());
            }
            if (unreachable) {
                throw new TestCaseBundleException("MinIO is down");
            }
            return super.get(path);
        }
    }

    @TestConfiguration
    static class Sources {
        @Bean
        @Primary
        RecordingSources recordingSources() {
            return new RecordingSources();
        }
    }

    @Autowired GrpcChannelFactory channels;
    @Autowired ProblemRepository problems;
    @Autowired RecordingSources sources;
    @MockitoBean TestCaseBundleStore bundleStore;
    @Value("${oj.rpc.token}") String token;

    private ManagedChannel channel;
    private ProblemInternalGrpc.ProblemInternalBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = channels.createChannel("problem-service");
        stub = ProblemInternalGrpc.newBlockingStub(channel).withInterceptors(new ServiceTokenClientInterceptor(token));
        when(bundleStore.findCurrentVersion(anyString())).thenReturn(Optional.of("f7837fd99ca5"));
        sources.transactionOpenOnCheckerRead.clear();
        sources.unreachable = false;
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** A problem whose row says it has a checker of this version (null: none); stores no source. */
    private String problem(String spjVersion) {
        String slug = "grpc-chk-" + UUID.randomUUID();
        problems.save(Problem.builder().title("checker").problemSlug(slug).status(ProblemStatus.ACTIVE.getValue())
                .timeLimit(1000).memoryLimit(256L).spj(spjVersion != null).spjVersion(spjVersion).build());
        return slug;
    }

    private void storeChecker(String slug, String source) {
        sources.put(slug + "/spj.cpp", source.getBytes(StandardCharsets.UTF_8));
    }

    private JudgeSpec spec(String slug) {
        return stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(slug).build());
    }

    @Test
    void checkerProblemSpecCarriesSourceAndVersion() throws Exception {
        String slug = problem(sha256(CHECKER));
        storeChecker(slug, CHECKER);

        JudgeSpec spec = spec(slug);

        assertThat(spec.getSpjSource()).isEqualTo(CHECKER);
        assertThat(spec.getSpjVersion()).isEqualTo(sha256(CHECKER));
        assertThat(spec.getTestCaseVersion()).isEqualTo("f7837fd99ca5");
        assertThat(sources.transactionOpenOnCheckerRead).as("read after the row's transaction").containsExactly(false);
    }

    @Test
    void plainProblemSpecHasNoChecker() {
        String slug = problem(null);

        JudgeSpec spec = spec(slug);

        assertThat(spec.getSpjSource()).isEmpty();
        assertThat(spec.getSpjVersion()).isEmpty();
        assertThat(sources.transactionOpenOnCheckerRead).as("no checker read").isEmpty();
    }

    @Test
    void missingCheckerSourceIsFailedPrecondition() throws Exception {
        String slug = problem(sha256(CHECKER));

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class, () -> spec(slug));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(e.getStatus().getDescription()).isEqualTo("Checker source missing for problem: " + slug);
    }

    @Test
    void unreadableCheckerStorageIsUnavailable() throws Exception {
        String slug = problem(sha256(CHECKER));
        storeChecker(slug, CHECKER);
        sources.unreachable = true;

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class, () -> spec(slug));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    /**
     * An upload stores the new source before its transaction commits the new version, so for a moment the row still
     * names the old one. qduoj keeps each sandbox's compiled checker by version, so the version sent always names the
     * source sent: otherwise a sandbox could keep the new checker under the old version's name.
     */
    @Test
    void theVersionSentNamesTheSourceSent() throws Exception {
        String replacement = CHECKER + "// v2\n";
        String slug = problem(sha256(CHECKER));
        storeChecker(slug, replacement);

        JudgeSpec spec = spec(slug);

        assertThat(spec.getSpjSource()).isEqualTo(replacement);
        assertThat(spec.getSpjVersion()).isEqualTo(sha256(replacement));
    }
}
