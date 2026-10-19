package vn.thanhtuanle.testcase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MinIO TLS configuration without a MinIO (the TLS round trip itself: MinioConfigTlsTest). */
class MinioConfigTest {

    @Test
    void tlsProfileUsesTheOjBundle() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-tls.yml"));

        assertThat(yaml.getObject()).containsEntry("minio.ssl-bundle", "oj");
    }

    @Test
    void bundleWithPlaintextEndpointFailsAtStartup() {
        MinioProperties props = new MinioProperties();
        props.setEndpoint("http://minio:9000");
        props.setSecretKey("secret-must-not-show");
        props.setSslBundle("oj");
        DefaultSslBundleRegistry bundles = new DefaultSslBundleRegistry("oj", SslBundle.of(
                new PemSslStoreBundle(null, PemSslStoreDetails.forCertificate("classpath:tls/ca.crt"))));

        // A TLS bundle must never quietly talk plaintext: the bean, and so the startup, fails.
        assertThatThrownBy(() -> new MinioConfig().minioClient(props, bundles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minio.ssl-bundle")
                .hasMessageContaining("minio.endpoint")
                .hasMessageNotContaining("secret-must-not-show");
    }
}
