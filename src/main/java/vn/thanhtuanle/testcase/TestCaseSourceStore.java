package vn.thanhtuanle.testcase;

import java.util.Optional;

/**
 * Where a test case's input and output files are kept — the source of truth for their contents. A path
 * is the value stored in {@code t_test_cases.input} / {@code output}, e.g. {@code a-plus-b/1.in}. Judge
 * bundles ({@link TestCaseBundleStore}) are built from these files and can always be rebuilt.
 */
public interface TestCaseSourceStore {

    void put(String path, byte[] content);

    /**
     * The file's content, or empty when nothing is stored at this path.
     *
     * @throws TestCaseBundleException on any other failure — an unreachable store never reads as "missing"
     */
    Optional<byte[]> get(String path);

    /** Same contract as {@link #get}, without downloading the content. */
    boolean exists(String path);

    /** Removes the file. Never throws: a leftover file is harmless, the next upload to the path replaces it. */
    void deleteQuietly(String path);
}
