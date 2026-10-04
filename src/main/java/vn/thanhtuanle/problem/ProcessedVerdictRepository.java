package vn.thanhtuanle.problem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.thanhtuanle.entity.ProcessedVerdict;

import java.time.LocalDateTime;
import java.util.UUID;

public interface ProcessedVerdictRepository extends JpaRepository<ProcessedVerdict, UUID> {

    /** @return 1 the first time this submission is seen, 0 every time after */
    @Modifying
    @Query(value = """
            INSERT INTO t_processed_verdicts (submission_id, processed_at) VALUES (:submissionId, :processedAt)
            ON CONFLICT (submission_id) DO NOTHING""", nativeQuery = true)
    int markProcessed(@Param("submissionId") UUID submissionId, @Param("processedAt") LocalDateTime processedAt);
}
