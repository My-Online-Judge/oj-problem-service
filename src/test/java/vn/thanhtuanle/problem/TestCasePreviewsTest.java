package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestCasePreviewsTest {

    @Test
    void shortTextUnchanged() {
        assertThat(TestCasePreviews.preview("1 2\n3")).isEqualTo("1 2\n3");
        assertThat(TestCasePreviews.preview("x".repeat(256))).hasSize(256);
    }

    @Test
    void cutsAtLastNewlineWithin256() {
        String text = "a".repeat(100) + "\n" + "b".repeat(100) + "\n" + "c".repeat(100);

        assertThat(TestCasePreviews.preview(text)).isEqualTo("a".repeat(100) + "\n" + "b".repeat(100));
    }

    @Test
    void noNewlineCutsAt256() {
        assertThat(TestCasePreviews.preview("z".repeat(1000))).isEqualTo("z".repeat(256));
    }

    @Test
    void nullStaysNull() {
        assertThat(TestCasePreviews.preview(null)).isNull();
    }
}
