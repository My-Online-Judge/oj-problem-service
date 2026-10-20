package vn.thanhtuanle.contest;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContestRepository extends JpaRepository<Contest, UUID> {

    /** The contest with its problems, each with its problem row, in one query. */
    @EntityGraph(attributePaths = {"problems", "problems.problem"})
    Optional<Contest> findBySlug(String slug);

    /**
     * The contest with this slug, row-locked until the transaction ends: concurrent changes of one contest run one
     * after the other, and each decides on what the one before committed. Its problems load lazily.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Contest c WHERE c.slug = :slug")
    Optional<Contest> findBySlugForUpdate(@Param("slug") String slug);

    boolean existsBySlug(String slug);

    /** The contest with this slug if it contains the problem with this slug, without its problems. */
    @Query("""
            SELECT c FROM ContestProblem cp JOIN cp.contest c JOIN cp.problem p
            WHERE c.slug = :contestSlug AND p.problemSlug = :problemSlug""")
    Optional<Contest> findBySlugContaining(@Param("contestSlug") String contestSlug,
            @Param("problemSlug") String problemSlug);

    /** Contests of these statuses that have not ended at {@code now} (running or upcoming), the earliest start first. */
    @Query("SELECT c FROM Contest c WHERE c.status IN :statuses AND c.endsAt > :now ORDER BY c.startsAt, c.slug")
    List<Contest> findNotEndedAt(@Param("statuses") Collection<ContestStatus> statuses, @Param("now") Instant now,
            Pageable page);

    /** Contests of these statuses that have ended at {@code now}, the latest end first. */
    @Query("SELECT c FROM Contest c WHERE c.status IN :statuses AND c.endsAt <= :now ORDER BY c.endsAt DESC, c.slug")
    List<Contest> findEndedAt(@Param("statuses") Collection<ContestStatus> statuses, @Param("now") Instant now,
            Pageable page);
}
