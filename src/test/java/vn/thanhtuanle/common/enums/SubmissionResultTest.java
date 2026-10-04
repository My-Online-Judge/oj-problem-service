package vn.thanhtuanle.common.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SubmissionResultTest {
    @Test
    void pendingAndJudgingAreNotTerminal() {
        assertThat(SubmissionResult.isTerminal(SubmissionResult.PENDING.getValue())).isFalse();
        assertThat(SubmissionResult.isTerminal(SubmissionResult.JUDGING.getValue())).isFalse();
    }

    @Test
    void verdictsAreTerminal() {
        assertThat(SubmissionResult.isTerminal(SubmissionResult.ACCEPTED.getValue())).isTrue();
        assertThat(SubmissionResult.isTerminal(SubmissionResult.WRONG_ANSWER.getValue())).isTrue();
        assertThat(SubmissionResult.isTerminal(SubmissionResult.COMPILE_ERROR.getValue())).isTrue();
        assertThat(SubmissionResult.isTerminal(SubmissionResult.SYSTEM_ERROR.getValue())).isTrue();
        assertThat(SubmissionResult.isTerminal(SubmissionResult.PARTIALLY_ACCEPTED.getValue())).isTrue();
    }
}
