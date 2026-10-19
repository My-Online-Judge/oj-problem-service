package vn.thanhtuanle.problem;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.thanhtuanle.entity.ProcessedVerdict;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedVerdictRepository extends JpaRepository<ProcessedVerdict, UUID> {

    /** @return 1 the first time this submission is seen (counted under {@code lastStatus}), 0 every time after */
    @Modifying
    @Query(value = """
            INSERT INTO t_processed_verdicts (submission_id, processed_at, last_status)
            VALUES (:submissionId, :processedAt, :lastStatus)
            ON CONFLICT (submission_id) DO NOTHING""", nativeQuery = true)
    int markProcessed(@Param("submissionId") UUID submissionId, @Param("processedAt") LocalDateTime processedAt,
                      @Param("lastStatus") int lastStatus);

    /** The submission's row, locked until the transaction ends: two verdicts of one submission take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProcessedVerdict> findForUpdateBySubmissionId(UUID submissionId);
}
