package vn.thanhtuanle.contest.dto;

import java.util.UUID;

/** A contestant: {@code username} and {@code name} are null when identity does not know the user or is down. */
public record ScoreboardUser(UUID id, String username, String name) {
}
