package vn.thanhtuanle.contest;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code t_contest_scores}, which has no entity: its rows are recomputed by SQL from the verdicts (Task 12). */
@Repository
@RequiredArgsConstructor
public class ContestScoreRepository {

    private static final RowMapper<ScoreRow> ROW = (rs, i) -> {
        OffsetDateTime acceptedAt = rs.getObject("accepted_at", OffsetDateTime.class);
        return new ScoreRow(rs.getObject("user_id", UUID.class), rs.getObject("problem_id", UUID.class),
                rs.getInt("attempts_before_ac"), acceptedAt == null ? null : acceptedAt.toInstant());
    };

    /**
     * Over the contestant's verdicts on the problem ({@code t_processed_verdicts}, each with its current
     * {@code last_status}): {@code accepted_at} is the earliest submit time of an accepted one, or null;
     * {@code attempts_before_ac} counts the others submitted before it (all of them while there is none), compile
     * errors (-2) and system errors (5) left out.
     */
    private static final String RECOMPUTE = """
            INSERT INTO t_contest_scores (contest_id, user_id, problem_id, attempts_before_ac, accepted_at)
            SELECT :contestId, :userId, :problemId,
                   count(*) FILTER (WHERE v.last_status NOT IN (0, -2, 5) AND (f.first_ac IS NULL OR v.submitted_at < f.first_ac)),
                   f.first_ac
            FROM t_processed_verdicts v
            CROSS JOIN (SELECT min(submitted_at) AS first_ac FROM t_processed_verdicts
                        WHERE contest_id = :contestId AND user_id = :userId AND problem_id = :problemId AND last_status = 0) f
            WHERE v.contest_id = :contestId AND v.user_id = :userId AND v.problem_id = :problemId
            GROUP BY f.first_ac
            ON CONFLICT (contest_id, user_id, problem_id)
            DO UPDATE SET attempts_before_ac = EXCLUDED.attempts_before_ac, accepted_at = EXCLUDED.accepted_at""";

    private final NamedParameterJdbcTemplate jdbc;

    /** Whether the contest has any score row. */
    public boolean existForContest(UUID contestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM t_contest_scores WHERE contest_id = :contestId)",
                Map.of("contestId", contestId), Boolean.class));
    }

    public List<ScoreRow> findByContestAndUser(UUID contestId, UUID userId) {
        return jdbc.query("""
                SELECT user_id, problem_id, attempts_before_ac, accepted_at
                FROM t_contest_scores WHERE contest_id = :contestId AND user_id = :userId""",
                Map.of("contestId", contestId, "userId", userId), ROW);
    }

    /**
     * Whether the contest exists. If it does, its row stays locked (FOR NO KEY UPDATE) until the transaction ends:
     * <ul>
     *   <li>score writes in one contest take turns, so a recompute reads every verdict an earlier one committed
     *       (its statement's snapshot would otherwise miss a verdict committed while it waited on the score row,
     *       and write over that row without it);</li>
     *   <li>a delete of the contest waits for this transaction, so a score row written meanwhile cannot fail its
     *       foreign key, and a delete already in flight is waited for, after which the contest reads as gone.</li>
     * </ul>
     * Readers are not blocked: plain reads of the contest take no row lock.
     */
    public boolean lockContest(UUID contestId) {
        return !jdbc.queryForList("SELECT 1 FROM t_contests WHERE id = :contestId FOR NO KEY UPDATE",
                Map.of("contestId", contestId), Integer.class).isEmpty();
    }

    /**
     * Rewrites the contestant's row on the problem from all of their verdicts there, so it ends the same whatever
     * the order the verdicts came in, however often each came, and after a rejudge.
     */
    public void recompute(UUID contestId, UUID userId, UUID problemId) {
        jdbc.update(RECOMPUTE, Map.of("contestId", contestId, "userId", userId, "problemId", problemId));
    }
}
