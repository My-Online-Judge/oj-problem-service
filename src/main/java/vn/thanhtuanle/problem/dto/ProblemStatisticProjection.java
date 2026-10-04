package vn.thanhtuanle.problem.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public interface ProblemStatisticProjection {
    UUID getId();

    LocalDateTime getCreatedAt();

    LocalDateTime getUpdatedAt();

    String getCreatedBy();

    String getUpdatedBy();

    String getTitle();

    String getDescription();

    String getSubject();

    int getTimeLimit();

    Long getMemoryLimit();

    int getHardnessLevel();

    String getProblemSlug();

    String getInputDescription();

    String getOutputDescription();

    String getHint();

    String getSampleInput();

    String getSampleOutput();

    int getStatus();

    int getTotalSubmission();

    int getAcceptedSubmission();
}
