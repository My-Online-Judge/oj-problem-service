package vn.thanhtuanle.contest;

import vn.thanhtuanle.contest.dto.ProblemCell;
import vn.thanhtuanle.contest.dto.ScoreboardRow;
import vn.thanhtuanle.contest.dto.ScoreboardUser;
import vn.thanhtuanle.oj.common.identity.PublicUser;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The ICPC ranking, a pure function of the score rows. A solved cell's minutes are the whole minutes (floored, and
 * never below 0) from the contest's start to the first accepted submit; the penalty is the sum over solved cells of
 * those minutes plus 20 per wrong try before the accepted one. Contestants rank by solved count descending, then
 * penalty ascending; equal solved and penalty share a rank. There is no freeze.
 */
public final class Scoreboard {

    static final int PENALTY_PER_WRONG_TRY = 20;

    private record Line(ScoreboardUser user, int solved, long penalty, SortedMap<String, ProblemCell> problems) {
    }

    /**
     * Solved desc, penalty asc, then username ignoring case (identity keeps usernames unique ignoring case but stores
     * them as typed; unknown last), then user id: the rank needs only the first two.
     */
    private static final Comparator<Line> ORDER = Comparator.comparingInt(Line::solved).reversed()
            .thenComparingLong(Line::penalty)
            .thenComparing(l -> l.user().username(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())))
            .thenComparing(l -> l.user().id());

    private Scoreboard() {
    }

    /**
     * Rows of a problem missing from {@code labelByProblem} (removed from the contest while it was a DRAFT) are
     * ignored, and so are rows with nothing counted (only compile or system errors: 0 tries, no AC). A contestant left
     * with no cell is not listed. {@code users} supplies the profiles; a user it lacks is listed without a name.
     */
    public static List<ScoreboardRow> rank(Instant startsAt, Map<UUID, String> labelByProblem, List<ScoreRow> rows,
            Map<UUID, PublicUser> users) {
        return rank(startsAt, labelByProblem, rows, users, Set.of());
    }

    /**
     * As {@link #rank(Instant, Map, List, Map)}, then every id in {@code enrolled} without a counted row as a zero
     * row (contest enrollment design §4.2): after the ranked rows, sharing the next rank, by username (nulls last,
     * ignoring case) then id. A REQUIRED contest's board lists everyone who registered.
     */
    public static List<ScoreboardRow> rank(Instant startsAt, Map<UUID, String> labelByProblem, List<ScoreRow> rows,
            Map<UUID, PublicUser> users, Set<UUID> enrolled) {
        Map<UUID, SortedMap<String, ProblemCell>> cellsByUser = new LinkedHashMap<>();
        for (ScoreRow row : rows) {
            String label = labelByProblem.get(row.problemId());
            if (label == null || !counts(row)) {
                continue;
            }
            boolean solved = row.acceptedAt() != null;
            // Never below 0: the recompute leaves out what was submitted before the start (Task 13 review I1), and
            // this keeps a row from before it, should one exist, from ranking first with a negative penalty.
            Long minutes = solved ? Math.max(0L, Duration.between(startsAt, row.acceptedAt()).toMinutes()) : null;
            cellsByUser.computeIfAbsent(row.userId(), u -> new TreeMap<>())
                    .put(label, new ProblemCell(solved, row.attemptsBeforeAc() + (solved ? 1 : 0), minutes));
        }

        List<Line> lines = new ArrayList<>();
        cellsByUser.forEach((userId, cells) -> {
            int solved = 0;
            long penalty = 0;
            for (ProblemCell cell : cells.values()) {
                if (cell.solved()) {
                    solved++;
                    penalty += cell.minutes() + (long) PENALTY_PER_WRONG_TRY * (cell.attempts() - 1);
                }
            }
            lines.add(new Line(user(userId, users.get(userId)), solved, penalty, cells));
        });
        lines.sort(ORDER);

        List<ScoreboardRow> ranked = new ArrayList<>(lines.size());
        int rank = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Line previous = i == 0 ? null : lines.get(i - 1);
            if (previous == null || previous.solved() != line.solved() || previous.penalty() != line.penalty()) {
                rank = i + 1; // 1 + the number of lines ahead: more solved, or as many and a lower penalty
            }
            ranked.add(new ScoreboardRow(rank, line.user(), line.solved(), line.penalty(), line.problems()));
        }
        List<Line> zero = enrolled.stream()
                .filter(id -> !cellsByUser.containsKey(id))
                .map(id -> new Line(user(id, users.get(id)), 0, 0L, new TreeMap<>()))
                .sorted(ORDER)
                .toList();
        int next = ranked.size() + 1;
        for (Line line : zero) {
            ranked.add(new ScoreboardRow(next, line.user(), 0, 0L, line.problems()));
        }
        return ranked;
    }

    /** Whether the row counts: solved, or wrong tries counted. Only compile or system errors (0, no AC) do not. */
    static boolean counts(ScoreRow row) {
        return row.acceptedAt() != null || row.attemptsBeforeAc() > 0;
    }

    private static ScoreboardUser user(UUID id, PublicUser profile) {
        return profile == null ? new ScoreboardUser(id, null, null)
                : new ScoreboardUser(id, profile.username(), profile.name());
    }
}
