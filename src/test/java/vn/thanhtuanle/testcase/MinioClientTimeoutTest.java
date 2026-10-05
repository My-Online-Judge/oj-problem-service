package vn.thanhtuanle.testcase;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
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
            MinioClient client = new MinioConfig().minioClient(props);

            Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> catchThrowable(() ->
                    client.bucketExists(BucketExistsArgs.builder().bucket("test-cases").build())));

            assertThat(thrown).isNotNull();
            assertThat(timedOut(thrown)).as("a SocketTimeoutException in the cause chain of %s", thrown).isTrue();
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
            if (t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
