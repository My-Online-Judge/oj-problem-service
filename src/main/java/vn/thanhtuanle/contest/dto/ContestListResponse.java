package vn.thanhtuanle.contest.dto;

import java.time.Instant;
import java.util.List;

/** The contests by phase, and the server's clock, which the portal's countdowns correct the browser's clock by. */
public record ContestListResponse(List<ContestSummary> running, List<ContestSummary> upcoming, List<ContestSummary> past,
        Instant serverTime) {
}
