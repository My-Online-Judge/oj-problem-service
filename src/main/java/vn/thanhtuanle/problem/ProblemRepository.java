package vn.thanhtuanle.problem;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.problem.dto.ProblemStatisticProjection;
import vn.thanhtuanle.problem.dto.ProblemStatisticsInfo;
import vn.thanhtuanle.problem.dto.ProblemTagRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProblemRepository extends JpaRepository<Problem, UUID>, JpaSpecificationExecutor<Problem> {
    /** True for every problem ever created with this slug, deleted ones included: a slug is never reused. */
    boolean existsByProblemSlug(String problemSlug);

    Page<Problem> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    Optional<Problem> findByProblemSlugAndStatusNot(String problemSlug, int status);

    /** The problem with this slug, unless it was deleted. */
    default Optional<Problem> findLiveBySlug(String problemSlug) {
        return findByProblemSlugAndStatusNot(problemSlug, ProblemStatus.DELETED.getValue());
    }

    @Query(value = """
            SELECT
                p.*,
                COUNT(s.id) AS totalSubmission,
                COUNT(CASE WHEN s.status = 0 THEN 1 END) AS acceptedSubmission
            FROM t_problems p
            LEFT JOIN t_submissions s ON p.id = s.problem_id
            WHERE (:search IS NULL OR p.title ILIKE CONCAT('%', :search, '%')
                   OR p.description ILIKE CONCAT('%', :search, '%'))
              AND (:status IS NULL OR p.status = :status)
              AND (:hardnessLevel IS NULL OR p.hardness_level = :hardnessLevel)
              AND p.status <> 2 -- ProblemStatus.DELETED
            GROUP BY p.id
            """, countQuery = """
            SELECT count(*) FROM t_problems p
            WHERE (:search IS NULL OR p.title ILIKE CONCAT('%', :search, '%'))
              AND (:status IS NULL OR p.status = :status)
              AND (:hardnessLevel IS NULL OR p.hardness_level = :hardnessLevel)
              AND p.status <> 2 -- ProblemStatus.DELETED
            """, nativeQuery = true)
    Page<ProblemStatisticProjection> findProblemsWithStats(String search, Integer status, Integer hardnessLevel,
            Pageable pageable);

    @Query(value = "SELECT problem_id AS problemId, tag AS tag FROM t_problem_tags WHERE problem_id IN (:ids) ORDER BY problem_id, tag", nativeQuery = true)
    List<ProblemTagRow> findTagsByProblemIds(@Param("ids") Collection<UUID> ids);

    @Query(value = """
            SELECT
                p.*,
                COUNT(s.id) AS totalSubmission,
                COUNT(CASE WHEN s.status = 0 THEN 1 END) AS acceptedSubmission
            FROM t_problems p
            LEFT JOIN t_submissions s ON p.id = s.problem_id
            WHERE p.problem_slug = :slug
              AND p.status <> 2 -- ProblemStatus.DELETED
            GROUP BY p.id
            """, nativeQuery = true)
    Optional<ProblemStatisticProjection> findByProblemSlugWithStats(@Param("slug") String slug);

    @Query(value = "SELECT status AS result, count(*) as count FROM t_submissions WHERE problem_id = :problemId GROUP BY status", nativeQuery = true)
    List<ProblemStatisticsInfo> countSubmissionsByResult(@Param("problemId") UUID problemId);
}
