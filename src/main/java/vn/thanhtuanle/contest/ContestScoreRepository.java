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

    private final NamedParameterJdbcTemplate jdbc;

    public List<ScoreRow> findByContestAndUser(UUID contestId, UUID userId) {
        return jdbc.query("""
                SELECT user_id, problem_id, attempts_before_ac, accepted_at
                FROM t_contest_scores WHERE contest_id = :contestId AND user_id = :userId""",
                Map.of("contestId", contestId, "userId", userId), ROW);
    }
}
