package vn.thanhtuanle.testcase;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

/** Test-case files as MinIO objects {@code sources/<path>}, next to the bundles in the same bucket. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MinioTestCaseSourceStore implements TestCaseSourceStore {

    static final String PREFIX = "sources/";

    private final MinioClient minio;
    private final MinioProperties props;

    @Override
    public void put(String path, byte[] content) {
        try (InputStream is = new ByteArrayInputStream(content)) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket()).object(key(path))
                    .stream(is, content.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new TestCaseBundleException("Failed to store test-case file " + path, e);
        }
    }

    @Override
    public Optional<byte[]> get(String path) {
        try (InputStream is = minio.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket()).object(key(path)).build())) {
            return Optional.of(is.readAllBytes());
        } catch (ErrorResponseException e) {
            if (TestCaseBundleStore.isMissing(e)) {
                return Optional.empty();
            }
            throw new TestCaseBundleException("Failed to read test-case file " + path, e);
        } catch (Exception e) {
            throw new TestCaseBundleException("Failed to read test-case file " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(props.getBucket()).object(key(path)).build());
            return true;
        } catch (ErrorResponseException e) {
            if (TestCaseBundleStore.isMissing(e)) {
                return false;
            }
            throw new TestCaseBundleException("Failed to stat test-case file " + path, e);
        } catch (Exception e) {
            throw new TestCaseBundleException("Failed to stat test-case file " + path, e);
        }
    }

    @Override
    public void deleteQuietly(String path) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(props.getBucket()).object(key(path)).build());
        } catch (Exception e) {
            log.warn("Could not delete test-case file {} (harmless): {}", path, e.getMessage());
        }
    }

    static String key(String path) {
        return PREFIX + path;
    }
}
