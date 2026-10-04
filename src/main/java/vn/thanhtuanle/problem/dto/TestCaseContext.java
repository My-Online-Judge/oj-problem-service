package vn.thanhtuanle.problem.dto;

/**
 * A test case's visibility and — only when {@code sample} is true — its file contents.
 * Hidden cases carry {@code null} content: it is never read from disk, so a downstream bug
 * cannot leak what was never loaded.
 */
public record TestCaseContext(boolean sample, String input, String expectedOutput) {

    public static TestCaseContext hidden() {
        return new TestCaseContext(false, null, null);
    }
}
