package vn.thanhtuanle.problem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A problem's test case exposed to admins. {@code name} is the numeric base (e.g. "1"), while
 * {@code input}/{@code output} carry the actual file <b>contents</b> (not the on-disk paths).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseResponse {
    private UUID id;
    private String name;
    private String input;
    private String output;
    private boolean sample;
}
