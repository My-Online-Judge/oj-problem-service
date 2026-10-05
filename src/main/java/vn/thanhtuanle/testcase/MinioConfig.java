package vn.thanhtuanle.testcase;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    MinioClient minioClient(MinioProperties props) {
        // MinIO's own client waits five minutes per read; a hung MinIO would hold every caller that long.
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(props.getConnectTimeout())
                .readTimeout(props.getReadTimeout())
                .writeTimeout(props.getReadTimeout())
                .build();
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .httpClient(http)
                .build();
    }
}
