package vn.thanhtuanle.contest;

import java.time.Instant;
import java.util.UUID;

/** One user's score on one contest problem: the wrong tries before the first accepted one, and when that came (or null). */
public record ScoreRow(UUID userId, UUID problemId, int attemptsBeforeAc, Instant acceptedAt) {
}
