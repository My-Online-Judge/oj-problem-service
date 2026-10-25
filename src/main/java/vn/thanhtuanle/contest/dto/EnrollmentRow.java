package vn.thanhtuanle.contest.dto;

import java.time.Instant;

/** One registered user, as the admin's participants list shows them (the username is null when identity cannot say). */
public record EnrollmentRow(ScoreboardUser user, Instant enrolledAt) {
}
