package vn.thanhtuanle.problem.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.test.LocalGrpcPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.thanhtuanle.ProblemServiceApplication;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.problem.ProblemRepository;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Under the tls profile the internal API (its own Netty server) speaks TLS, requires a client certificate signed by the
 * CA, and answers only the CNs in {@code oj.tls.grpc-allowed-clients} (application-tls.yml: submission-service). The
 * certificate replaces the service token: as deployed, {@code oj.rpc.token} is unset. Test-only fixtures:
 * src/test/resources/tls.
 */
@SpringBootTest(properties = {
        "oj.rpc.token=",
        // the tls profile configures management SSL, which needs a separate management port
        "management.server.port=0",
        // the tls profile sets minio.ssl-bundle, which requires an https endpoint (no MinIO is contacted here)
        "minio.endpoint=https://localhost:9000"})
@ActiveProfiles({"test", "tls"})
class GrpcMutualTlsTest {

    private static Path tlsDir;

    @LocalGrpcPort
    int port;
    @Autowired
    ProblemRepository problems;
    @MockitoBean
    TestCaseBundleStore bundleStore;

    @DynamicPropertySource
    static void certificates(DynamicPropertyRegistry registry) throws IOException {
        tlsDir = Files.createTempDirectory("oj-tls");
        for (Map.Entry<String, String> f : Map.of("server.crt", "tls.crt", "server.key", "tls.key", "ca.crt", "ca.crt")
                .entrySet()) {
            try (InputStream in = fixture(f.getKey())) {
                Files.copy(in, tlsDir.resolve(f.getValue()));
            }
        }
        registry.add("OJ_TLS_DIR", tlsDir::toString);
    }

    @AfterAll
    static void deleteTlsDir() throws IOException {
        try (var files = Files.walk(tlsDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void submissionCertGetsJudgeSpec() throws IOException {
        // NOT_FOUND comes from the service itself: the call passed TLS and the peer check.
        assertThat(codeOf("submission-service", "no-such-problem")).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void probeCertGetsPermissionDenied() throws IOException {
        assertThat(codeOf("tls-probe", "no-such-problem")).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void noCertFailsWithUnavailable() throws IOException {
        StatusRuntimeException refused = failureOf(null, "no-such-problem");

        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        // TLS 1.3 refuses a missing client certificate after the handshake, with this alert. A plaintext or an
        // untrusted server would fail the call with UNAVAILABLE as well, for another reason.
        assertThat(causeChain(refused)).contains("certificate_required");
    }

    @Test
    void noTokenNeeded() throws IOException {
        String slug = "mtls-" + UUID.randomUUID();
        problems.save(Problem.builder().title("mtls").problemSlug(slug).status(ProblemStatus.ACTIVE.getValue())
                .timeLimit(1000).memoryLimit(256L).testCases(new ArrayList<>()).build());
        when(bundleStore.findCurrentVersion(slug)).thenReturn(Optional.of("f7837fd99ca5"));

        // The stub sends no x-oj-service-token header.
        ManagedChannel channel = channel("submission-service");
        try {
            JudgeSpec spec = ProblemInternalGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(slug).build());
            assertThat(spec.getProblemSlug()).isEqualTo(slug);
            assertThat(spec.getTestCaseVersion()).isEqualTo("f7837fd99ca5");
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void startupFailsWithoutAnyGrpcAuth() {
        // No tls profile (so no allowlist) and no token: the internal API would be open to anyone on the network.
        assertThat(startupFailure("--oj.rpc.token="))
                .contains("problem-service gRPC needs oj.tls.grpc-allowed-clients or oj.rpc.token");
    }

    @Test
    void aSetTokenStillNeeds32Characters() {
        assertThat(startupFailure("--oj.rpc.token=too-short")).contains("at least 32 characters");
    }

    /** Starts the application with the test profile plus {@code args}; returns the failure's message chain. */
    private static String startupFailure(String... args) {
        String[] all = new String[args.length + 4];
        all[0] = "--spring.profiles.active=test";
        all[1] = "--spring.jpa.hibernate.ddl-auto=none";
        all[2] = "--server.port=0";
        all[3] = "--spring.grpc.server.port=0";
        System.arraycopy(args, 0, all, 4, args.length);
        ConfigurableApplicationContext started = null;
        Throwable failure = null;
        try {
            started = new SpringApplicationBuilder(ProblemServiceApplication.class).run(all);
        } catch (Throwable e) {
            failure = e;
        } finally {
            if (started != null) {
                started.close();
            }
        }
        assertThat(failure).as("the application must not start").isNotNull();
        return causeChain(failure);
    }

    /** The status of a GetJudgeSpec call presenting the fixture {@code identity} (no client certificate when null). */
    private Status.Code codeOf(String identity, String slug) throws IOException {
        StatusRuntimeException failure = failureOf(identity, slug);
        return failure == null ? Status.Code.OK : failure.getStatus().getCode();
    }

    /** The failure of a GetJudgeSpec call presenting {@code identity}, or null when it succeeded. */
    private StatusRuntimeException failureOf(String identity, String slug) throws IOException {
        ManagedChannel channel = channel(identity);
        try {
            ProblemInternalGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug(slug).build());
            return null;
        } catch (StatusRuntimeException e) {
            return e;
        } finally {
            channel.shutdownNow();
        }
    }

    private static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t).append('\n');
        }
        return chain.toString();
    }

    /** A channel to the server's port trusting the test CA and presenting {@code identity} (none when null). */
    private ManagedChannel channel(String identity) throws IOException {
        TlsChannelCredentials.Builder credentials = TlsChannelCredentials.newBuilder();
        try (InputStream ca = fixture("ca.crt")) {
            credentials.trustManager(ca);
        }
        if (identity != null) {
            try (InputStream crt = fixture(identity + ".crt"); InputStream key = fixture(identity + ".key")) {
                credentials.keyManager(crt, key);
            }
        }
        return NettyChannelBuilder.forAddress("localhost", port, credentials.build()).build();
    }

    private static InputStream fixture(String name) {
        InputStream in = GrpcMutualTlsTest.class.getResourceAsStream("/tls/" + name);
        if (in == null) {
            throw new IllegalStateException("test fixture missing from the classpath: /tls/" + name
                    + " (regenerate with judge-deployment/pki/pki.sh fixtures)");
        }
        return in;
    }
}
