package vn.thanhtuanle.problem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateTestCaseSampleRequest {
    @NotNull
    private Boolean sample;
}
