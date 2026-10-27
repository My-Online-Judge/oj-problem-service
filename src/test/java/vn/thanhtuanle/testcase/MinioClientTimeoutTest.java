package vn.thanhtuanle.testcase;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** A MinIO that accepts the connection and never answers must fail the call within the read timeout, not hang. */
class MinioClientTimeoutTest {

    @Test
    void aSilentMinioFailsWithinTheReadTimeout() throws IOException {
        try (ServerSocket silent = new ServerSocket(0)) { // the kernel accepts; nobody ever reads or answers
            MinioProperties props = new MinioProperties();
            props.setEndpoint("http://127.0.0.1:" + silent.getLocalPort());
            props.setReadTimeout(Duration.ofMillis(300));
            MinioClient client = new MinioConfig().minioClient(props, new DefaultSslBundleRegistry());

            long start = System.nanoTime();
            Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> catchThrowable(() ->
                    client.bucketExists(BucketExistsArgs.builder().bucket("test-cases").build())));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(thrown).isNotNull();
            // MinIO 9 drops the outer SocketTimeoutException and keeps the socket OkHttp closed on timeout, so the chain
            // may hold either: what matters is that the call gave up on its own, near the read timeout.
            assertThat(timedOut(thrown)).as("a socket timeout or close in the cause chain of %s", thrown).isTrue();
            assertThat(took).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    void theDefaultsAreTwoSecondsToConnectAndTenToRead() {
        MinioProperties props = new MinioProperties();
        assertThat(props.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof SocketException) {
                return true;
            }
        }
        return false;
    }
}
