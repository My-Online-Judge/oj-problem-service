package vn.thanhtuanle.problem.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.problem.ProblemTags;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProblemDto {
    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must be at most 255 characters")
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
    @Size(max = ProblemStatementLimits.MAX_STATEMENT_FIELD)
    private String inputDescription;

    @NotBlank(message = "Output description is required")
    @Size(max = ProblemStatementLimits.MAX_STATEMENT_FIELD)
    private String outputDescription;

    @NotBlank(message = "Sample input is required")
    @Size(max = ProblemStatementLimits.MAX_STATEMENT_FIELD)
    private String sampleInput;

    @NotBlank(message = "Sample output is required")
    @Size(max = ProblemStatementLimits.MAX_STATEMENT_FIELD)
    private String sampleOutput;

    @Size(max = ProblemStatementLimits.MAX_STATEMENT_FIELD)
    private String hint;

    
    @Size(max = ProblemTags.MAX_TAGS, message = "A problem has at most 10 tags")
    private List<String> tags;

    private ProblemStatus status;
}
