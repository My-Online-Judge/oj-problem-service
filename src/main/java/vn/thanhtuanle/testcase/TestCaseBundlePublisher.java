package vn.thanhtuanle.testcase;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.TestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a problem's judge bundle from its test-case rows and their stored files. The rows decide what
 * is in the bundle: a row whose file is missing fails the publish instead of silently leaving the test
 * case out (which is how tests 2–4 of simple-a-plus-b vanished from judging).
 */
@Component
@RequiredArgsConstructor
public class TestCaseBundlePublisher {

    private final TestCaseSourceStore sources;
    private final TestCaseBundleStore bundles;

    /** Uploads the bundle (unless an identical one exists) and points CURRENT at it; returns its hash. */
    public String publish(Problem problem) {
        return bundles.publish(problem.getProblemSlug(), files(problem));
    }

    /** The hash {@link #publish} would produce now, without uploading anything. */
    public String bundleHash(Problem problem) {
        return TestCaseBundleStore.contentHash(BundleFile.sortedByName(files(problem)));
    }

    private List<BundleFile> files(Problem problem) {
        List<TestCaseBundleBuilder.SourcePair> pairs = new ArrayList<>();
        for (TestCase tc : problem.getTestCases()) {
            pairs.add(new TestCaseBundleBuilder.SourcePair(
                    fileName(tc.getInput()), read(tc.getInput()),
                    fileName(tc.getOutput()), read(tc.getOutput())));
        }
        if (pairs.isEmpty()) {
            throw new TestCaseBundleException("Problem " + problem.getProblemSlug() + " has no test cases");
        }
        return TestCaseBundleBuilder.build(pairs);
    }

    private byte[] read(String path) {
        return sources.get(path).orElseThrow(() -> new TestCaseBundleException(
                "Test-case file " + path + " is not stored; upload it again or delete its test case"));
    }

    static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
