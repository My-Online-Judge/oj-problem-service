package vn.thanhtuanle.problem;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.common.payload.ApiResponse;
import vn.thanhtuanle.problem.dto.TestCaseResponse;
import vn.thanhtuanle.problem.dto.UpdateTestCaseSampleRequest;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(Routes.PROBLEM_TEST_CASES)
@RequiredArgsConstructor
@Slf4j
public class TestCaseController {

    private final TestCaseService testCaseService;

    @GetMapping
    @Operation(summary = "List a problem's test cases with file contents (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<List<TestCaseResponse>> listTestCases(@PathVariable String slug) {
        log.info("Start list test cases: slug={}", slug);
        return ApiResponse.success(testCaseService.listTestCases(slug));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Add a single test case (input + output files) to a problem (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<TestCaseResponse> addTestCase(
            @PathVariable String slug,
            @RequestPart("input") MultipartFile input,
            @RequestPart("output") MultipartFile output) throws IOException {
        log.info("Start add test case: slug={}", slug);
        return ApiResponse.created(testCaseService.addTestCase(slug, input, output));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import test cases from a ZIP into a problem, appended after existing (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<List<TestCaseResponse>> importTestCases(
            @PathVariable String slug,
            @RequestPart(AppProperties.REQUEST_PART_FILE) MultipartFile file) throws IOException {
        log.info("Start import test cases: slug={}", slug);
        return ApiResponse.created(testCaseService.importTestCases(slug, file));
    }

    @DeleteMapping("/{testCaseId}")
    @Operation(summary = "Delete a test case from a problem (admin only)")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<Void> deleteTestCase(
            @PathVariable String slug,
            @PathVariable UUID testCaseId) {
        log.info("Start delete test case: slug={}, id={}", slug, testCaseId);
        testCaseService.deleteTestCase(slug, testCaseId);
        return ApiResponse.success();
    }

    @PatchMapping("/{testCaseId}")
    @Operation(summary = "Mark a test case as sample (visible) or hidden")
    @PreAuthorize("hasAuthority('problem:update')")
    public ApiResponse<TestCaseResponse> setSample(
            @PathVariable String slug,
            @PathVariable UUID testCaseId,
            @Valid @RequestBody UpdateTestCaseSampleRequest body) {
        log.info("Start set test case sample: slug={}, id={}, sample={}", slug, testCaseId, body.getSample());
        return ApiResponse.success(testCaseService.setSample(slug, testCaseId, body.getSample()));
    }
}
