package vn.thanhtuanle.problem.dto;

import java.util.UUID;

/**
 * A test case in the admin list: sizes (UTF-8 bytes) and short previews instead of the full contents, which
 * {@code GET .../test-cases/{id}} serves on demand.
 */
public record TestCaseSummary(UUID id, String name, boolean sample, long inputSize, long outputSize,
                              String inputPreview, String outputPreview) {
}
