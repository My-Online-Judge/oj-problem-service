package vn.thanhtuanle.contest.enrollment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Enrollment.Key> {

    /** How many users registered for each of these contests; a contest with none has no row. */
    interface EnrollmentCount {
        UUID getContestId();
        long getEnrolled();
    }

    boolean existsByKeyContestIdAndKeyUserId(UUID contestId, UUID userId);

    long countByKeyContestId(UUID contestId);

    @Query("""
            SELECT e.key.contestId AS contestId, count(e) AS enrolled
            FROM Enrollment e WHERE e.key.contestId IN :ids
            GROUP BY e.key.contestId""")
    List<EnrollmentCount> countByContestIds(@Param("ids") Collection<UUID> ids);

    /** Of these contests, the ones the user registered for. */
    @Query("SELECT e.key.contestId FROM Enrollment e WHERE e.key.userId = :userId AND e.key.contestId IN :ids")
    Set<UUID> contestIdsOf(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /** Every registered user of a contest, in no particular order (the scoreboard lists them). */
    @Query("SELECT e.key.userId FROM Enrollment e WHERE e.key.contestId = :contestId")
    Set<UUID> userIdsOf(@Param("contestId") UUID contestId);

    Page<Enrollment> findByKeyContestIdOrderByEnrolledAtDesc(UUID contestId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM Enrollment e WHERE e.key.contestId = :contestId AND e.key.userId = :userId")
    int deleteByContestAndUser(@Param("contestId") UUID contestId, @Param("userId") UUID userId);
}
