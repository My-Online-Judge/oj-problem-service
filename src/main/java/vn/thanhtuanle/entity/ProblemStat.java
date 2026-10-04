package vn.thanhtuanle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * How many submissions of a problem ended with a verdict. Only terminal verdicts are counted: the rows are
 * built from {@code SubmissionVerdictRecorded} events (and seeded from oj-db at the cutover).
 */
@Entity
@Table(name = "t_problem_stats")
@IdClass(ProblemStat.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProblemStat {

    @Id
    @Column(name = "problem_id")
    private UUID problemId;

    /** A {@code SubmissionResult} value. */
    @Id
    private int verdict;

    @Column(name = "submission_count", nullable = false)
    private long submissionCount;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID problemId;
        private int verdict;
    }
}
