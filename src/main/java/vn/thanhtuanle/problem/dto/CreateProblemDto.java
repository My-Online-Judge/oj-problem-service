package vn.thanhtuanle.problem.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.thanhtuanle.common.enums.ProblemStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProblemDto {
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

    @NotBlank(message = "Problem slug is required")
    @Pattern(regexp = "^[a-zA-Z0-9-]*$", message = "Problem slug must contain only alphanumeric characters and hyphens")
    private String problemSlug;

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
