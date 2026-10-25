package vn.thanhtuanle.contest;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.contest.dto.ProblemCell;
import vn.thanhtuanle.contest.dto.ScoreboardRow;
import vn.thanhtuanle.contest.dto.ScoreboardUser;
import vn.thanhtuanle.oj.common.identity.PublicUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The ICPC ranking over score rows, for a contest that starts at 10:00. */
class ScoreboardTest {

    private static final Instant START = at("10:00:00");
    private static final UUID PA = UUID.randomUUID();
    private static final UUID PB = UUID.randomUUID();
    private static final UUID PC = UUID.randomUUID();
    private static final Map<UUID, String> LABELS = Map.of(PA, "A", PB, "B", PC, "C");

    private static Instant at(String time) {
        return Instant.parse("2026-10-21T" + time + "Z");
    }

    private static ScoreRow row(UUID user, UUID problem, int attemptsBeforeAc, Instant acceptedAt) {
        return new ScoreRow(user, problem, attemptsBeforeAc, acceptedAt);
    }

    private static List<ScoreboardRow> rank(ScoreRow... rows) {
        return Scoreboard.rank(START, LABELS, List.of(rows), Map.of());
    }

    private static UUID user() {
        return UUID.randomUUID();
    }

    @Test
    void penaltyIsMinutesPlusTwentyPerWrongAttempt() {
        UUID u = user();

        List<ScoreboardRow> board = rank(row(u, PA, 2, at("10:15:00")), row(u, PB, 0, at("10:30:00")));

        assertThat(board).hasSize(1);
        ScoreboardRow line = board.get(0);
        assertThat(line.rank()).isEqualTo(1);
        assertThat(line.solved()).isEqualTo(2);
        assertThat(line.penalty()).isEqualTo(15 + 40 + 30);
        assertThat(line.problems()).isEqualTo(Map.of(
                "A", new ProblemCell(true, 3, 15L),
                "B", new ProblemCell(true, 1, 30L)));
    }

    @Test
    void minutesAreFloored() {
        UUID u = user();

        ScoreboardRow line = rank(row(u, PA, 0, at("10:15:59"))).get(0);

        assertThat(line.problems().get("A").minutes()).isEqualTo(15L);
        assertThat(line.penalty()).isEqualTo(15);
    }

    @Test
    void rankBySolvedThenPenalty() {
        UUID twoSlow = user(), oneFast = user(), twoFast = user();

        List<ScoreboardRow> board = rank(
                row(twoSlow, PA, 2, at("10:15:00")), row(twoSlow, PB, 0, at("10:30:00")), // 2 solved, 85
                row(oneFast, PA, 0, at("10:05:00")),                                       // 1 solved, 5
                row(twoFast, PA, 0, at("10:20:00")), row(twoFast, PB, 1, at("10:20:00"))); // 2 solved, 60

        assertThat(board).extracting(r -> r.user().id(), ScoreboardRow::rank, ScoreboardRow::solved, ScoreboardRow::penalty)
                .containsExactly(tuple(twoFast, 1, 2, 60L), tuple(twoSlow, 2, 2, 85L), tuple(oneFast, 3, 1, 5L));
    }

    @Test
    void tiesShareARank() {
        UUID first = user(), alsoFirst = user(), third = user();

        List<ScoreboardRow> board = rank(
                row(first, PA, 0, at("10:15:00")),
                row(alsoFirst, PB, 0, at("10:15:30")),
                row(third, PA, 0, at("10:30:00")));

        assertThat(board).extracting(ScoreboardRow::rank).containsExactly(1, 1, 3);
        assertThat(board.get(2).user().id()).isEqualTo(third);
    }

    @Test
    void unsolvedProblemsAddNoPenalty() {
        UUID u = user();

        List<ScoreboardRow> board = rank(row(u, PA, 3, null));

        assertThat(board).hasSize(1);
        assertThat(board.get(0).problems()).isEqualTo(Map.of("A", new ProblemCell(false, 3, null)));
        assertThat(board.get(0).solved()).isZero();
        assertThat(board.get(0).penalty()).isZero();
        assertThat(board.get(0).rank()).isEqualTo(1);
    }

    /**
     * Compile and system errors leave a row with nothing counted (0 attempts, no AC; Task 12). A contestant with
     * only such rows is left out; such a cell is left out of a listed contestant's line.
     */
    @Test
    void usersWithNothingCountedAreOmitted() {
        UUID onlyErrors = user(), tried = user(), solvedOne = user();

        List<ScoreboardRow> board = rank(
                row(onlyErrors, PA, 0, null), row(onlyErrors, PB, 0, null),
                row(tried, PA, 1, null),
                row(solvedOne, PA, 0, null), row(solvedOne, PB, 0, at("10:20:00")));

        assertThat(board).extracting(r -> r.user().id()).containsExactly(solvedOne, tried);
        assertThat(board.get(0).problems()).isEqualTo(Map.of("B", new ProblemCell(true, 1, 20L)));
    }

    /** Score rows of a problem removed from the contest (while it was a DRAFT) neither show nor count. */
    @Test
    void rowsOfProblemsNoLongerInTheContestAreIgnored() {
        UUID removedOnly = user(), both = user();
        UUID removed = UUID.randomUUID();

        List<ScoreboardRow> board = rank(
                row(removedOnly, removed, 0, at("10:01:00")),
                row(both, removed, 2, at("10:02:00")), row(both, PC, 1, at("10:10:00")));

        assertThat(board).hasSize(1);
        assertThat(board.get(0).user().id()).isEqualTo(both);
        assertThat(board.get(0).solved()).isEqualTo(1);
        assertThat(board.get(0).penalty()).isEqualTo(10 + 20);
        assertThat(board.get(0).problems()).containsOnlyKeys("C");
    }

    /** Equal solved and penalty: by username, unknown users last, then by id. Profiles come from identity. */
    @Test
    void tiesAreOrderedByUsernameNullsLastThenId() {
        UUID bob = user(), alice = user(), unknown1 = user(), unknown2 = user();
        Map<UUID, PublicUser> users = Map.of(
                bob, new PublicUser(bob, "bob", "Bob B", null, null),
                alice, new PublicUser(alice, "alice", "Alice A", null, null));
        List<UUID> unknown = unknown1.compareTo(unknown2) < 0 ? List.of(unknown1, unknown2) : List.of(unknown2, unknown1);

        List<ScoreboardRow> board = Scoreboard.rank(START, LABELS, List.of(
                row(unknown2, PA, 0, at("10:10:00")),
                row(bob, PA, 0, at("10:10:00")),
                row(unknown1, PA, 0, at("10:10:00")),
                row(alice, PA, 0, at("10:10:00"))), users);

        assertThat(board).extracting(ScoreboardRow::user).containsExactly(
                new ScoreboardUser(alice, "alice", "Alice A"),
                new ScoreboardUser(bob, "bob", "Bob B"),
                new ScoreboardUser(unknown.get(0), null, null),
                new ScoreboardUser(unknown.get(1), null, null));
        assertThat(board).extracting(ScoreboardRow::rank).containsOnly(1);
    }

    /**
     * A score row accepted before the start (a contest postponed while its first submissions waited for a verdict,
     * Task 13 review I1; the recompute leaves such submissions out, this is the second line of defence) is 0 minutes,
     * never negative.
     */
    @Test
    void acceptedBeforeTheStartIsNeverNegative() {
        UUID early = user(), onTime = user();

        List<ScoreboardRow> board = rank(row(early, PA, 1, at("09:30:00")), row(onTime, PA, 0, at("10:05:00")));

        assertThat(board).extracting(r -> r.user().id(), ScoreboardRow::rank, ScoreboardRow::penalty)
                .containsExactly(tuple(onTime, 1, 5L), tuple(early, 2, 20L));
        assertThat(board.get(1).problems()).isEqualTo(Map.of("A", new ProblemCell(true, 2, 0L)));
    }

    /** identity keeps usernames unique ignoring case but stores them as typed, so the tiebreak ignores case. */
    @Test
    void theUsernameTiebreakIgnoresCase() {
        UUID zed = user(), alice = user();
        Map<UUID, PublicUser> users = Map.of(
                zed, new PublicUser(zed, "Zed", "Zed Z", null, null),
                alice, new PublicUser(alice, "alice", "Alice A", null, null));

        List<ScoreboardRow> board = Scoreboard.rank(START, LABELS, List.of(
                row(zed, PA, 0, at("10:10:00")),
                row(alice, PA, 0, at("10:10:00"))), users);

        assertThat(board).extracting(r -> r.user().username()).containsExactly("alice", "Zed");
    }

    @Test
    void enrolledUsersWithoutCountedRowsFollowTheRankedRowsWithASharedRank() {
        // Contest enrollment design §4.2: a REQUIRED contest lists every registered user; those who never had a
        // counted submission follow the ranked rows, share the next rank, and go by username (nulls last), then id.
        UUID fast = user(), slow = user(), x = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
                y = user(), z = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Map<UUID, PublicUser> users = Map.of(
                x, new PublicUser(x, "zed", null, null, null),
                z, new PublicUser(z, "amy", null, null, null));

        List<ScoreboardRow> board = Scoreboard.rank(START, LABELS,
                List.of(row(fast, PA, 0, at("10:05:00")), row(slow, PA, 0, at("10:20:00")), row(y, PB, 0, null)),
                users, Set.of(x, y, z, fast));   // y has a row but it does not count (no accept, no wrong try)

        assertThat(board).extracting(ScoreboardRow::rank, r -> r.user().id(), ScoreboardRow::solved)
                .containsExactly(tuple(1, fast, 1), tuple(2, slow, 1), tuple(3, z, 0), tuple(3, x, 0), tuple(3, y, 0));
        assertThat(board.get(2).problems()).isEmpty();
        assertThat(board.get(2).penalty()).isZero();
        // The 4-arg form is the old behaviour: no zero rows.
        assertThat(Scoreboard.rank(START, LABELS, List.of(row(fast, PA, 0, at("10:05:00"))), Map.of())).hasSize(1);
    }
}
