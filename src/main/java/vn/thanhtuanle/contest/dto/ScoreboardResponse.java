package vn.thanhtuanle.contest.dto;

import java.util.List;

/** The contest's labels in contest order, and its contestants by rank. */
public record ScoreboardResponse(List<String> labels, List<ScoreboardRow> rows) {
}
