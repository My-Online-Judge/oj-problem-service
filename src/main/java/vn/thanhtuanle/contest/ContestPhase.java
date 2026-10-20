package vn.thanhtuanle.contest;

import java.time.Instant;

/** Where a contest is in time. It runs over the half-open window [startsAt, endsAt). */
public enum ContestPhase {
    UPCOMING,
    RUNNING,
    ENDED;

    public static ContestPhase of(Instant startsAt, Instant endsAt, Instant now) {
        if (now.isBefore(startsAt)) {
            return UPCOMING;
        }
        return now.isBefore(endsAt) ? RUNNING : ENDED;
    }
}
