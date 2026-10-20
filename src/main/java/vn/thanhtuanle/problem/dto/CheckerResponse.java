package vn.thanhtuanle.problem.dto;

/**
 * A problem's checker: whether it has one, the sha256 hex of its source ({@code version}), and the source itself,
 * which only {@code GET /problems/{slug}/checker} fills. {@code version} and {@code source} are null without a checker.
 */
public record CheckerResponse(boolean spj, String version, String source) {
}
