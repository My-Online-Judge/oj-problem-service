package vn.thanhtuanle.testcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving test-case files from the container's disk to MinIO must not change any bundle: a new hash
 * would make every judge-worker download the bundle again and, worse, would mean the judge now sees
 * different files. The fixtures in {@code src/test/resources/bundle-golden} cover a CRLF output, an
 * output without a final newline, UTF-8 text and the index 10 (which sorts before 2 as a string).
 */
class TestCaseBundleBuilderGoldenTest {

    /**
     * {@code expected-info} and this hash were produced by the directory-based code this replaced
     * ({@code GenerateTestCaseInfoUtil} + {@code TestCaseBundleStore.listBundleFiles/contentHash}) on
     * the same fixture files.
     */
    private static final String OLD_CODE_HASH = "da85635b1715";

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = TestCaseBundleBuilderGoldenTest.class.getResourceAsStream("/bundle-golden/" + name)) {
            return in.readAllBytes();
        }
    }

    private static List<TestCaseBundleBuilder.SourcePair> pairs() throws IOException {
        List<TestCaseBundleBuilder.SourcePair> pairs = new ArrayList<>();
        for (String n : List.of("1", "2", "10")) {
            pairs.add(new TestCaseBundleBuilder.SourcePair(n + ".in", fixture(n + ".in"), n + ".out", fixture(n + ".out")));
        }
        return pairs;
    }

    @Test
    void theInfoFileIsByteIdenticalToTheOldOne() throws IOException {
        assertThat(new String(TestCaseBundleBuilder.info(pairs()), StandardCharsets.UTF_8))
                .isEqualTo(new String(fixture("expected-info"), StandardCharsets.UTF_8));
    }

    @Test
    void theBundleHashIsTheOldOne() throws IOException {
        List<BundleFile> files = BundleFile.sortedByName(TestCaseBundleBuilder.build(pairs()));

        assertThat(files).extracting(BundleFile::name)
                .containsExactly("1.in", "1.out", "10.in", "10.out", "2.in", "2.out", "info");
        assertThat(TestCaseBundleStore.contentHash(files)).isEqualTo(OLD_CODE_HASH);
    }

    @Test
    void theOrderOfTheRowsDoesNotChangeTheBundle() throws IOException {
        List<TestCaseBundleBuilder.SourcePair> reversed = pairs();
        Collections.reverse(reversed);

        assertThat(TestCaseBundleStore.contentHash(BundleFile.sortedByName(TestCaseBundleBuilder.build(reversed))))
                .isEqualTo(OLD_CODE_HASH);
    }
}
