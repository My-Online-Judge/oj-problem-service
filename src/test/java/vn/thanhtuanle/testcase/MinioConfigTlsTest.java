package vn.thanhtuanle.testcase;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.messages.ListAllMyBucketsResult.Bucket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.security.cert.CertificateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * MinIO over TLS (sub-project 10): with {@code minio.ssl-bundle} set, MinioConfig's client trusts the bundle's CA (and
 * presents the bundle's certificate); without it the JVM's default trust store refuses MinIO's private-CA certificate.
 * MinIO serves the test-only fixture server certificate (src/test/resources/tls) from --certs-dir.
 */
@Testcontainers(disabledWithoutDocker = true)
class MinioConfigTlsTest {

    // Upstream MinIO images are gone; the org mirrors the exact image the stack runs (see judge-deployment).
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("ghcr.io/my-online-judge/minio:RELEASE.2025-09-07T16-13-09Z")
            .withCommand("server", "/data", "--certs-dir", "/certs")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.crt"), "/certs/public.crt")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/certs/private.key")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.crt"), "/certs/CAs/ca.crt")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttps("/minio/health/live").forPort(9000).allowInsecure());

    @Test
    void bundleClientListsBucket() throws Exception {
        MinioClient client = new MinioConfig().minioClient(props("oj"), ojBundle());

        client.makeBucket(MakeBucketArgs.builder().bucket("test-cases").build());

        assertThat(client.listBuckets()).extracting(Bucket::name).contains("test-cases");
    }

    @Test
    void defaultClientRejectsPrivateCa() {
        MinioClient client = new MinioConfig().minioClient(props(null), new DefaultSslBundleRegistry());

        // MinIO 9 rethrows the handshake failure as an IllegalStateException around the certificate check's own error.
        assertThat(catchThrowable(client::listBuckets)).hasCauseInstanceOf(CertificateException.class);
    }

    private static MinioProperties props(String sslBundle) {
        MinioProperties props = new MinioProperties();
        props.setEndpoint("https://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        props.setAccessKey("minioadmin");
        props.setSecretKey("minioadmin");
        props.setSslBundle(sslBundle);
        return props;
    }

    /** The bundle the tls profile defines as "oj": a CA-signed client certificate and the test CA as trust anchor. */
    private static SslBundles ojBundle() {
        PemSslStoreDetails keyStore = new PemSslStoreDetails(null, "classpath:tls/tls-probe.crt", "classpath:tls/tls-probe.key");
        PemSslStoreDetails trustStore = PemSslStoreDetails.forCertificate("classpath:tls/ca.crt");
        return new DefaultSslBundleRegistry("oj", SslBundle.of(new PemSslStoreBundle(keyStore, trustStore)));
    }
}
