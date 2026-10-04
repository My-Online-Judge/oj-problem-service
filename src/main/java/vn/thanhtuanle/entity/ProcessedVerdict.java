package vn.thanhtuanle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** A submission whose verdict is already in {@link ProblemStat}: events arrive at least once, counts must not. */
@Entity
@Table(name = "t_processed_verdicts")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedVerdict {

    @Id
    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}
