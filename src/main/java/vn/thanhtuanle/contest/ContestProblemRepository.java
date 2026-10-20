package vn.thanhtuanle.contest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContestProblemRepository extends JpaRepository<ContestProblem, ContestProblem.Key> {

    interface ProblemCount {
        UUID getContestId();

        long getProblems();
    }

    /** How many problems each of these contests has; a contest without problems has no row. */
    @Query("""
            SELECT cp.contest.id AS contestId, count(cp) AS problems
            FROM ContestProblem cp WHERE cp.contest.id IN :ids GROUP BY cp.contest.id""")
    List<ProblemCount> countByContestIds(@Param("ids") Collection<UUID> ids);
}
