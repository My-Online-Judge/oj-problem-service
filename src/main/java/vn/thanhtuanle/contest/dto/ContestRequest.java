package vn.thanhtuanle.contest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.thanhtuanle.contest.ContestStatus;

import java.time.Instant;
import java.util.List;

/**
 * A contest to create or update. {@code slug} is required on create (the {@link OnCreate} group) and ignored on
 * update. {@code problemSlugs} is required on create; on update it may be left out (null), which keeps the list as
 * it is, because a caller without {@code problem:update} does not see an upcoming contest's problems to send them
 * back. The problems get their labels from their order: A, B, C …
 */
public record ContestRequest(
        @NotNull(groups = OnCreate.class, message = "Slug is required")
        @Pattern(groups = OnCreate.class, regexp = "^[a-z0-9-]{3,64}$",
                message = "Slug must be 3 to 64 lowercase letters, digits or hyphens")
        String slug,
        @NotBlank(message = "Title is required") @Size(max = 255, message = "Title must be at most 255 characters")
        String title,
        @Size(max = 65536, message = "Description must be at most 65536 characters")
        String description,
        @NotNull(message = "Start time is required") Instant startsAt,
        @NotNull(message = "End time is required") Instant endsAt,
        @NotNull(message = "Status is required") ContestStatus status,
        @NotNull(groups = OnCreate.class, message = "Problems are required")
        @Size(min = 1, max = 26, message = "A contest has 1 to 26 problems")
        List<String> problemSlugs) {

    /** The rules that apply on create only. */
    public interface OnCreate {
    }
}
