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
}
