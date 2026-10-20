package vn.thanhtuanle.problem.packaging;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;

import java.io.IOException;

@RestController
@RequestMapping(Routes.PROBLEMS)
@RequiredArgsConstructor
@Slf4j
public class ProblemPackageController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final ProblemPackageService packages;

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a problem from a package zip (admin only)")
    @PreAuthorize("hasAuthority('problem:create')")
    public ApiResponse<ProblemResponseDto> importPackage(
            @RequestPart(AppProperties.REQUEST_PART_FILE) MultipartFile file,
            @RequestParam(required = false) String slugOverride) throws IOException {
        log.info("Start import problem package: slugOverride={}", slugOverride);
        return ApiResponse.created(packages.importPackage(file, slugOverride));
    }

    /** The problem and its files are checked before this returns; only then does the zip stream. */
    @GetMapping("/{slug}/export")
    @Operation(summary = "Download a problem as a package zip (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ResponseEntity<StreamingResponseBody> exportPackage(@PathVariable String slug) {
        log.info("Start export problem package: {}", slug);
        ProblemPackageService.PackagePlan plan = packages.plan(slug);
        return ResponseEntity.ok()
                .contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(slug + ".zip").build().toString())
                .body(out -> packages.write(plan, out));
    }

    /** A taken slug is a conflict here; oj-common answers it with 400 everywhere else. */
    @ExceptionHandler(ResourceAlreadyExistException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleSlugTaken(ResourceAlreadyExistException e) {
        log.warn("Package import refused: {}", e.getMessage());
        return ApiResponse.error(HttpStatus.CONFLICT.value(), e.getMessage());
    }
}
