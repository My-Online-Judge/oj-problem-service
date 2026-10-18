package vn.thanhtuanle.problem.dto;

/** Size caps for problem text, shared by the create and update DTOs. */
public final class ProblemStatementLimits {
    /** Characters allowed in each statement field (hint, input/output description, samples). */
    public static final int MAX_STATEMENT_FIELD = 65536;

    private ProblemStatementLimits() {
    }
}
