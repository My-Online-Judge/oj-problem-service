package vn.thanhtuanle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A submission whose verdict is already in {@link ProblemStat}: events arrive at least once, counts must not. The
 * verdict it is counted under is kept so that a rejudge can move the count.
 */
@Entity
@Table(name = "t_processed_verdicts")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedVerdict {

    @Id
    @Column(name = "submission_id")
    private UUID submissionId;

    @Setter
    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    /** The {@code SubmissionResult} value counted for this submission; null when it was counted before V5. */
    @Setter
    @Column(name = "last_status")
    private Integer lastStatus;
}
