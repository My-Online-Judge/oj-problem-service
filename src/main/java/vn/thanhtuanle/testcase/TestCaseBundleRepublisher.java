package vn.thanhtuanle.testcase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.problem.ProblemRepository;

import java.util.List;
import java.util.Optional;

/**
 * When the bundle format changes ({@link TestCaseBundleBuilder#FORMAT_VERSION}) the stored bundles are stale: the same
 * test cases now produce a different bundle. Once per format version this rebuilds every live problem whose bundle hash
 * moved. A marker object ({@link TestCaseBundleStore#FORMAT_MARKER_KEY}) records the version done; it is written only when
 * every problem succeeded, so a failed pass runs again at the next start. The pass runs on a daemon thread: start-up
 * does not wait for it. (Two replicas starting together would both run it; republishing is idempotent, so that only
 * costs time.)
 */
@Component
@Order(2) // after TestCaseStorageBootstrap
@Slf4j
public class TestCaseBundleRepublisher implements ApplicationRunner {

    private final TestCaseBundleStore store;
    private final TestCaseBundlePublisher publisher;
    private final ProblemRepository problems;
    private final TransactionOperations tx;
    private final boolean enabled;

    public TestCaseBundleRepublisher(TestCaseBundleStore store, TestCaseBundlePublisher publisher,
                                     ProblemRepository problems, TransactionOperations tx,
                                     @Value("${oj.testcases.republish-on-start:false}") boolean enabled) {
        this.store = store;
        this.publisher = publisher;
        this.problems = problems;
        this.tx = tx;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                republishIfFormatChanged();
            } catch (RuntimeException e) {
                log.error("Bundle republish pass failed; it runs again at the next start: {}", e.getMessage());
            }
        }, "bundle-republish");
        t.setDaemon(true);
        t.start();
    }

    /** Returns how many problems were republished. */
    public int republishIfFormatChanged() {
        String version = TestCaseBundleBuilder.FORMAT_VERSION;
        if (store.readText(TestCaseBundleStore.FORMAT_MARKER_KEY).filter(version::equals).isPresent()) {
            return 0;
        }
        List<String> slugs = problems.findLiveSlugs();
        int republished = 0;
        boolean allOk = true;
        for (String slug : slugs) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> republish(slug)))) {
                    republished++;
                }
            } catch (RuntimeException e) {
                allOk = false;
                log.error("Bundle format {}: republishing {} failed: {}", version, slug, e.getMessage());
            }
        }
        if (allOk) {
            store.writeText(TestCaseBundleStore.FORMAT_MARKER_KEY, version);
        }
        log.info("Bundle format {}: republished {} of {} problems", version, republished, slugs.size());
        return republished;
    }

    /** Under the problem's row lock, so a concurrent test-case change cannot interleave. */
    private boolean republish(String slug) {
        Optional<Problem> locked = problems.findBySlugForUpdate(slug);
        if (locked.isEmpty()) {
            return false; // deleted since the slug list was read
        }
        Problem problem = locked.get();
        if (problem.getTestCases().isEmpty()) {
            return false; // never had a bundle, nothing to rebuild
        }
        String hash = publisher.bundleHash(problem);
        if (store.findCurrentVersion(slug).filter(hash::equals).isPresent()) {
            return false;
        }
        publisher.publish(problem);
        return true;
    }
}
