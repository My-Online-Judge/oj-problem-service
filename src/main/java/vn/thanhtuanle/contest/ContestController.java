package vn.thanhtuanle.contest;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.common.security.Authorities;
import vn.thanhtuanle.contest.dto.ContestDetail;
import vn.thanhtuanle.contest.dto.ContestListResponse;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.problem.ProblemAccess;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping(Routes.CONTESTS)
@RequiredArgsConstructor
@Slf4j
public class ContestController {

    /** Who sees DRAFT contests; with problem:update too, also their problems, and problems before the start. */
    static final String EDITOR_AUTHORITY = "contest:update";

    private final ContestService contests;

    @GetMapping
    @Operation(summary = "Contests by phase: running, upcoming, past")
    public ApiResponse<ContestListResponse> list() {
        return ApiResponse.success(contests.list(editor()));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "A contest, with the caller's progress when signed in")
    public ApiResponse<ContestDetail> detail(@PathVariable String slug) {
        return ApiResponse.success(contests.detail(slug, editor(), ProblemAccess.callerMayEdit(), viewer()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a contest (admin only)")
    @PreAuthorize("hasAuthority('contest:create')")
    public ApiResponse<ContestDetail> create(
            @Validated({Default.class, ContestRequest.OnCreate.class}) @RequestBody ContestRequest request) {
        return ApiResponse.created(contests.create(request, ProblemAccess.callerMayEdit()));
    }

    @PutMapping("/{slug}")
    @Operation(summary = "Update a contest (admin only)")
    @PreAuthorize("hasAuthority('contest:update')")
    public ApiResponse<ContestDetail> update(@PathVariable String slug, @Valid @RequestBody ContestRequest request) {
        return ApiResponse.success(contests.update(slug, request, ProblemAccess.callerMayEdit()));
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "Delete a contest (admin only)")
    @PreAuthorize("hasAuthority('contest:delete')")
    public ApiResponse<Void> delete(@PathVariable String slug) {
        contests.delete(slug);
        return ApiResponse.success();
    }

    @GetMapping("/{slug}/problems/{label}")
    @Operation(summary = "A contest's problem by label, once the contest has started")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProblemResponseDto> problem(@PathVariable String slug, @PathVariable String label) {
        return ApiResponse.success(contests.problem(slug, label, editor() && ProblemAccess.callerMayEdit()));
    }

    /** A taken slug is a conflict here; oj-common answers it with 400 everywhere else. */
    @ExceptionHandler(ResourceAlreadyExistException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleSlugTaken(ResourceAlreadyExistException e) {
        log.warn("Contest create refused: {}", e.getMessage());
        return ApiResponse.error(HttpStatus.CONFLICT.value(), e.getMessage());
    }

    private static boolean editor() {
        return Authorities.has(EDITOR_AUTHORITY);
    }

    /**
     * The signed-in caller, from the access token only. A request without a usable token (none, or expired: the JWT
     * filter is lenient), or a token without a uid, is anonymous here, where {@code CurrentUser.id()} would throw.
     */
    private static Optional<UUID> viewer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return Optional.ofNullable(token.getToken().getClaimAsString(OjJwtDecoders.UID_CLAIM)).map(UUID::fromString);
        }
        return Optional.empty();
    }
}
