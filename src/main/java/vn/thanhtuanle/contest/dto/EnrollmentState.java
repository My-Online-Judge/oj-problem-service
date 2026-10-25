package vn.thanhtuanle.contest.dto;

/** The answer to a register or withdraw: whether the caller is now registered, and how many are. */
public record EnrollmentState(boolean enrolled, int enrolledCount) {
}
