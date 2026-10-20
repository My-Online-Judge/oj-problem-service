package vn.thanhtuanle.contest.dto;

import vn.thanhtuanle.contest.ContestPhase;
import vn.thanhtuanle.contest.ContestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One contest. {@code problems} is empty before the start for anyone but an editor; {@code myProgress} is the
 * caller's progress by label, for the problems the caller has tried, and empty for an anonymous caller.
 */
public record ContestDetail(String slug, String title, String description, Instant startsAt, Instant endsAt,
        ContestStatus status, ContestPhase phase, List<ContestProblemView> problems,
        Map<String, ProblemProgress> myProgress, Instant serverTime) {
}
