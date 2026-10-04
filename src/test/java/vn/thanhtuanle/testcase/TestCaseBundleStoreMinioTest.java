package vn.thanhtuanle.testcase;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real MinIO: CURRENT points at what publish wrote, and a never-published problem has no version. */
@Testcontainers(disabledWithoutDocker = true)
class TestCaseBundleStoreMinioTest {

    // Upstream MinIO images are gone; the org mirrors the exact image the stack runs (see judge-deployment).
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("ghcr.io/my-online-judge/minio:RELEASE.2025-09-07T16-13-09Z")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private TestCaseBundleStore store;

    @BeforeEach
    void setUp() {
        MinioProperties props = new MinioProperties();
        props.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        props.setBucket("test-cases");
        store = new TestCaseBundleStore(
                MinioClient.builder().endpoint(props.getEndpoint()).credentials("minioadmin", "minioadmin").build(),
                props);
        store.ensureBucket();
    }

    private static BundleFile file(String name, String content) {
        return new BundleFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void currentPointsAtThePublishedBundle() {
        String hash = store.publish("p", List.of(file("1.in", "1 2\n"), file("1.out", "3\n"), file("info", "{}")));

        assertThat(store.findCurrentVersion("p")).contains(hash);
    }

    @Test
    void aProblemWithoutABundleHasNoVersion() {
        assertThat(store.findCurrentVersion("never-published")).isEmpty();
    }
}
