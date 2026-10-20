package vn.thanhtuanle.contest.dto;

/** {@code attempts} counts the accepted try too: 1 wrong answer, then accepted, is 2 attempts. */
public record ProblemProgress(boolean solved, int attempts) {
}
