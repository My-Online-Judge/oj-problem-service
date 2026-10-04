package vn.thanhtuanle.problem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import vn.thanhtuanle.common.payload.ApiResponse;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.problem.dto.CreateProblemDto;
import vn.thanhtuanle.problem.dto.UpdateProblemDto;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping(Routes.PROBLEMS)
@RequiredArgsConstructor
@Slf4j
public class ProblemController {

    private final ProblemService problemService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Create a new problem (admin only)")
    @PreAuthorize("hasAuthority('problem:create')")
    public ApiResponse<ProblemResponseDto> createProblem(
            @Valid @RequestPart(AppProperties.REQUEST_PART_DATA) CreateProblemDto dto,
            @RequestPart(AppProperties.REQUEST_PART_FILE) MultipartFile file) throws IOException {

        log.info("Start create problem: title={}, slug={}", dto.getTitle(), dto.getProblemSlug());
        ProblemResponseDto problem = problemService.createProblem(dto, file);
        log.info("END create problem: ID={}", problem.getId());
        return ApiResponse.created(problem);
    }

    @GetMapping
    @Operation(summary = "Get list of problems")
    public ApiResponse<List<ProblemResponseDto>> getProblems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ProblemStatus status,
            @RequestParam(required = false) Integer hardnessLevel) {

        log.info("Start get problems: page={}, size={}, search={}, status={}, hardnessLevel={}",
                page, size, search, status, hardnessLevel);
        return ApiResponse.success(problemService.getProblems(page, size, search, status, hardnessLevel));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get problem by slug")
    public ApiResponse<ProblemResponseDto> getProblemBySlug(@PathVariable String slug) {
        log.info("Start get problem by slug: {}", slug);
        return ApiResponse.success(problemService.getProblemBySlug(slug));
    }

    @PutMapping("/{slug}")
    @Operation(summary = "Update a problem's metadata (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<ProblemResponseDto> updateProblem(
            @PathVariable String slug,
            @Valid @RequestBody UpdateProblemDto dto) {
        log.info("Start update problem: {}", slug);
        return ApiResponse.success(problemService.updateProblem(slug, dto));
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "Delete a problem (admin only)")
    @PreAuthorize("hasAuthority('problem:delete')")
    public ApiResponse<Void> deleteProblem(@PathVariable String slug) {
        log.info("Start delete problem: {}", slug);
        problemService.deleteProblem(slug);
        return ApiResponse.success();
    }
}
