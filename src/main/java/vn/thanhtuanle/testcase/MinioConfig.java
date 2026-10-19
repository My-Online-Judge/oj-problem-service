package vn.thanhtuanle.testcase;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.net.ssl.X509TrustManager;
import java.util.Arrays;

@Configuration
public class MinioConfig {

    @Bean
    MinioClient minioClient(MinioProperties props, SslBundles sslBundles) {
        // MinIO's own client waits five minutes per read; a hung MinIO would hold every caller that long.
        OkHttpClient.Builder http = new OkHttpClient.Builder()
                .connectTimeout(props.getConnectTimeout())
                .readTimeout(props.getReadTimeout())
                .writeTimeout(props.getReadTimeout());
        if (StringUtils.hasText(props.getSslBundle())) {
            // A TLS bundle with a plaintext endpoint would quietly skip TLS: refuse to start instead.
            if (props.getEndpoint() == null || !props.getEndpoint().regionMatches(true, 0, "https://", 0, 8)) {
                throw new IllegalStateException("minio.ssl-bundle is set, so minio.endpoint must start with https://");
            }
            // TLS (sub-project 10): MinIO's certificate is verified against the bundle's CA (and its host name, by
            // OkHttp), not the JVM's default trust store, which does not know the private CA.
            SslBundle bundle = sslBundles.getBundle(props.getSslBundle());
            X509TrustManager trustManager = Arrays.stream(bundle.getManagers().getTrustManagers())
                    .filter(X509TrustManager.class::isInstance)
                    .map(X509TrustManager.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "SSL bundle '" + props.getSslBundle() + "' has no X509TrustManager"));
            http.sslSocketFactory(bundle.createSslContext().getSocketFactory(), trustManager);
        }
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .httpClient(http.build())
                .build();
    }
}
