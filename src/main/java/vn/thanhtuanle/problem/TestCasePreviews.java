package vn.thanhtuanle.problem;

/** Short previews of test-case files for the admin list. */
final class TestCasePreviews {

    static final int MAX_CHARS = 256;

    private TestCasePreviews() {
    }

    /** The first 256 characters, cut at the last newline inside that window when there is one; null stays null. */
    static String preview(String text) {
        if (text == null || text.length() <= MAX_CHARS) {
            return text;
        }
        String window = text.substring(0, MAX_CHARS);
        int newline = window.lastIndexOf('\n');
        return newline >= 0 ? window.substring(0, newline) : window;
    }
}
