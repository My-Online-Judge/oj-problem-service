package vn.thanhtuanle.testcase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Checks on startup that the test-case bucket exists. In the stack, minio-init (judge-deployment) creates it before this
 * service starts, and the service's own MinIO key may not create buckets (minio/policies/problem-rw.json). Where the key
 * may, as with the root account in development, a missing bucket is created here, so a fresh MinIO volume works from the
 * first problem on. A MinIO that cannot be reached, or a missing bucket this key may not create, is logged, not fatal:
 * problem pages and judging still work, only writing test cases fails until it is fixed.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class TestCaseStorageBootstrap implements ApplicationRunner {

    private final TestCaseBundleStore bundleStore;

    @Override
    public void run(ApplicationArguments args) {
        try {
            bundleStore.ensureBucket();
        } catch (TestCaseBundleException e) {
            log.error("Test-case bucket not checked or not created (MinIO unreachable, or the bucket missing and this key "
                    + "may not create it); creating problems and test cases fails until it is: {}", e.getMessage());
        }
    }
}
