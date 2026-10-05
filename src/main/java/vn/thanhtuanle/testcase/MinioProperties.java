package vn.thanhtuanle.testcase;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "minio")
@Getter
@Setter
public class MinioProperties {
    private String endpoint = "http://localhost:9000";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private String bucket = "test-cases";
    /** How many bundle versions to keep per problem (current + previous). */
    private int bundleRetention = 3;
    /** Time allowed to open a connection to MinIO. */
    private Duration connectTimeout = Duration.ofSeconds(2);
    /** Time allowed for each read or write on an open connection: a MinIO that stops answering fails the call. */
    private Duration readTimeout = Duration.ofSeconds(10);
}
