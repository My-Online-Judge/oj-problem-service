package vn.thanhtuanle.contest.enrollment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * Registers the user unless already registered: 1 row inserted, or 0. One statement, so two registers of the same
     * user that race (two tabs, a retried request) both answer "registered"; a check-then-insert would hand the loser
     * a duplicate-key error once the winner commits.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO t_contest_enrollments (contest_id, user_id, enrolled_at)
            VALUES (:contestId, :userId, :enrolledAt)
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("contestId") UUID contestId, @Param("userId") UUID userId,
            @Param("enrolledAt") Instant enrolledAt);

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

    /** The whole list, newest first (a search spans it). */
    List<Enrollment> findByKeyContestIdOrderByEnrolledAtDesc(UUID contestId);

    /**
     * A bulk delete bypasses the persistence context: it is flushed first and cleared after, so a registration row
     * deleted here is not kept as a managed instance that a later register (a merge) would silently update.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Enrollment e WHERE e.key.contestId = :contestId AND e.key.userId = :userId")
    int deleteByContestAndUser(@Param("contestId") UUID contestId, @Param("userId") UUID userId);
}
