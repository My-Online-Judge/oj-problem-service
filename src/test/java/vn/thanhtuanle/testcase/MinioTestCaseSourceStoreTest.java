package vn.thanhtuanle.testcase;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MinioTestCaseSourceStoreTest {

    // Upstream MinIO images are gone; the org mirrors the exact image the stack runs (see judge-deployment).
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("ghcr.io/my-online-judge/minio:RELEASE.2025-09-07T16-13-09Z")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private MinioClient client;
    private MinioProperties props;
    private MinioTestCaseSourceStore store;

    @BeforeEach
    void setUp() {
        props = new MinioProperties();
        props.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        props.setBucket("test-cases");
        client = MinioClient.builder().endpoint(props.getEndpoint()).credentials("minioadmin", "minioadmin").build();
        new TestCaseBundleStore(client, props).ensureBucket();
        store = new MinioTestCaseSourceStore(client, props);
    }

    @Test
    void aStoredFileIsReadBackFromUnderTheSourcesPrefix() throws Exception {
        store.put("a-plus-b/1.in", "1 2\n".getBytes(StandardCharsets.UTF_8));

        assertThat(store.get("a-plus-b/1.in")).hasValueSatisfying(
                bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("1 2\n"));
        assertThat(store.exists("a-plus-b/1.in")).isTrue();
        // Next to (not inside) the bundles' <slug>/ prefix, so bundle GC never sees these objects.
        client.statObject(StatObjectArgs.builder().bucket("test-cases").object("sources/a-plus-b/1.in").build());
    }

    @Test
    void aMissingFileReadsAsEmptyNotAsAnError() {
        assertThat(store.get("nope/1.in")).isEmpty();
        assertThat(store.exists("nope/1.in")).isFalse();
    }

    @Test
    void aDeletedFileIsGone() {
        store.put("a-plus-b/2.in", new byte[] {1});

        store.deleteQuietly("a-plus-b/2.in");

        assertThat(store.get("a-plus-b/2.in")).isEmpty();
    }

    @Test
    void anUnreachableStoreFailsInsteadOfReadingAsMissing() {
        MinioProperties down = new MinioProperties();
        down.setBucket("test-cases");
        MinioClient unreachable = MinioClient.builder().endpoint("http://127.0.0.1:1")
                .credentials("minioadmin", "minioadmin").build();
        MinioTestCaseSourceStore broken = new MinioTestCaseSourceStore(unreachable, down);

        assertThatThrownBy(() -> broken.get("a-plus-b/1.in")).isInstanceOf(TestCaseBundleException.class);
        assertThatThrownBy(() -> broken.exists("a-plus-b/1.in")).isInstanceOf(TestCaseBundleException.class);
    }
}
