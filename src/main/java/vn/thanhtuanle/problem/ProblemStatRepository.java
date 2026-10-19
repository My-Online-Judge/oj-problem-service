package vn.thanhtuanle.problem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.thanhtuanle.entity.ProblemStat;

import java.util.UUID;

public interface ProblemStatRepository extends JpaRepository<ProblemStat, ProblemStat.Key> {

    /** One more submission of this problem ended with this verdict. */
    @Modifying
    @Query(value = """
            INSERT INTO t_problem_stats (problem_id, verdict, submission_count) VALUES (:problemId, :verdict, 1)
            ON CONFLICT (problem_id, verdict) DO UPDATE SET submission_count = t_problem_stats.submission_count + 1""",
            nativeQuery = true)
    void increment(@Param("problemId") UUID problemId, @Param("verdict") int verdict);

    /** One submission of this problem no longer ends with this verdict (a rejudge changed it). Never below zero. */
    @Modifying
    @Query(value = """
            UPDATE t_problem_stats SET submission_count = submission_count - 1
            WHERE problem_id = :problemId AND verdict = :verdict AND submission_count > 0""", nativeQuery = true)
    void decrement(@Param("problemId") UUID problemId, @Param("verdict") int verdict);
}
