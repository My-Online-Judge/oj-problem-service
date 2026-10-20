package vn.thanhtuanle.problem;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.problem.dto.CheckerResponse;

@RestController
@RequestMapping(Routes.PROBLEMS + "/{slug}/checker")
@RequiredArgsConstructor
@Slf4j
public class CheckerController {

    private final CheckerService checkers;

    @GetMapping
    @Operation(summary = "Read a problem's checker with its source (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<CheckerResponse> getChecker(@PathVariable String slug) {
        log.info("Start get checker: slug={}", slug);
        return ApiResponse.success(checkers.get(slug));
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace a problem's C++ checker, at most 64 KiB (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<CheckerResponse> uploadChecker(
            @PathVariable String slug,
            @RequestPart(AppProperties.REQUEST_PART_FILE) MultipartFile file) {
        log.info("Start upload checker: slug={}, bytes={}", slug, file.getSize());
        return ApiResponse.success(checkers.upload(slug, file));
    }

    @DeleteMapping
    @Operation(summary = "Remove a problem's checker: outputs are compared again (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<Void> removeChecker(@PathVariable String slug) {
        log.info("Start remove checker: slug={}", slug);
        checkers.remove(slug);
        return ApiResponse.success();
    }
}
