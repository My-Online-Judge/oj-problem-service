package vn.thanhtuanle.testcase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the test-case bucket when it does not exist yet, so a fresh MinIO volume works from the first
 * problem on (in 2a the one-time backfill did this; it left with it). A MinIO that cannot be reached is
 * logged, not fatal: problem pages and judging still work, only writing test cases fails until it is back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestCaseStorageBootstrap implements ApplicationRunner {

    private final TestCaseBundleStore bundleStore;

    @Override
    public void run(ApplicationArguments args) {
        try {
            bundleStore.ensureBucket();
        } catch (TestCaseBundleException e) {
            log.error("Test-case bucket not checked, MinIO unreachable; creating problems and test cases fails until it is: {}",
                    e.getMessage());
        }
    }
}
