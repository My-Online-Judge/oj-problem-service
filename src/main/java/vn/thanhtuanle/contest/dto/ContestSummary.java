package vn.thanhtuanle.contest.dto;

import vn.thanhtuanle.contest.ContestPhase;
import vn.thanhtuanle.contest.ContestStatus;

import java.time.Instant;

public record ContestSummary(String slug, String title, Instant startsAt, Instant endsAt, ContestStatus status,
        ContestPhase phase, int problemCount) {
}
