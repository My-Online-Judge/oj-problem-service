package vn.thanhtuanle.contest.dto;

import java.util.Map;

/**
 * One contestant's line: rows with equal {@code solved} and {@code penalty} share a {@code rank}. {@code problems}
 * holds a cell per label the contestant has something counted on, in label order.
 */
public record ScoreboardRow(int rank, ScoreboardUser user, int solved, long penalty, Map<String, ProblemCell> problems) {
}
