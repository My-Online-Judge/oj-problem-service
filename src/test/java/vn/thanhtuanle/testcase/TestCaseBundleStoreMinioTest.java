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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The backfill recovers files from the bundle CURRENT points at: what publish writes, it must read back. */
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
    void theFilesOfThePublishedBundleAreReadBack() {
        String hash = store.publish("p", List.of(file("1.in", "1 2\n"), file("1.out", "3\n"), file("info", "{}")));

        Map<String, byte[]> files = store.currentBundleFiles("p");

        assertThat(store.currentVersion("p")).isEqualTo(hash);
        assertThat(files).containsOnlyKeys("1.in", "1.out", "info");
        assertThat(new String(files.get("1.out"), StandardCharsets.UTF_8)).isEqualTo("3\n");
    }

    @Test
    void aProblemWithoutABundleHasNoFiles() {
        assertThat(store.currentBundleFiles("never-published")).isEmpty();
    }
}
