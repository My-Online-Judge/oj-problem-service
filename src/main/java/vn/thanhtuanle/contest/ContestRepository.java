package vn.thanhtuanle.contest;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsBySlug(String slug);

    /** Contests of these statuses that have not ended at {@code now}: running or upcoming. */
    @Query("SELECT c FROM Contest c WHERE c.status IN :statuses AND c.endsAt > :now")
    List<Contest> findNotEndedAt(@Param("statuses") Collection<ContestStatus> statuses, @Param("now") Instant now);

    /** Contests of these statuses that have ended at {@code now}, the latest end first. */
    @Query("SELECT c FROM Contest c WHERE c.status IN :statuses AND c.endsAt <= :now ORDER BY c.endsAt DESC, c.slug")
    List<Contest> findEndedAt(@Param("statuses") Collection<ContestStatus> statuses, @Param("now") Instant now,
            Pageable page);
}
