package vn.thanhtuanle.problem.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vn.thanhtuanle.common.payload.BaseResponse;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ProblemResponseDto extends BaseResponse {
    private String title;
    private String subject;
    private String description;
    private Integer timeLimit;
    private Integer memoryLimit;
    private Integer hardnessLevel;
    private String problemSlug;
    private String sampleInput;
    private String sampleOutput;
    private String inputDescription;
    private String outputDescription;
    private String hint;
    private int status;
    private Integer totalSubmission;
    private Integer acceptedSubmission;
    private Map<String, Integer> statisticInfo;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
