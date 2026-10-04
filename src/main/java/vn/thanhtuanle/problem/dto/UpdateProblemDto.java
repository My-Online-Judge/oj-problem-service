package vn.thanhtuanle.problem.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.thanhtuanle.common.enums.ProblemStatus;

/**
 * Metadata update for a problem. The slug (identity) and test-case files are NOT changed here —
 * re-uploading test cases would be a separate, file-based operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProblemDto {
    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Subject is required")
    private String subject;

    private String description;

    @Min(value = 1, message = "Time limit must be at least 1 ms")
    private int timeLimit;

    @Min(value = 1, message = "Memory limit must be at least 1 MB")
    private int memoryLimit;

    @Min(value = 1, message = "Hardness level must be at least 1")
    private int hardnessLevel;

    @NotBlank(message = "Input description is required")
    private String inputDescription;

    @NotBlank(message = "Output description is required")
    private String outputDescription;

    @NotBlank(message = "Sample input is required")
    private String sampleInput;

    @NotBlank(message = "Sample output is required")
    private String sampleOutput;

    private String hint;

    private ProblemStatus status;
}
