package vn.thanhtuanle.contest.dto;

/**
 * One contestant on one problem. {@code attempts} counts the accepted try too (compile and system errors never
 * count); {@code minutes} is the whole minutes from the contest's start to the first accepted submit, null when
 * unsolved.
 */
public record ProblemCell(boolean solved, int attempts, Long minutes) {
}
