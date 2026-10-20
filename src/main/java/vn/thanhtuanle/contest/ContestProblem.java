package vn.thanhtuanle.contest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.thanhtuanle.entity.Problem;

import java.io.Serializable;
import java.util.UUID;

/** A problem of a contest under its label: the key is (contest, label), and a problem appears once per contest. */
@Entity
@Table(name = "t_contest_problems")
@IdClass(ContestProblem.Key.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ContestProblem {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id")
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Id
    @Column(nullable = false)
    private char label;

    @Column(nullable = false)
    private int position;

    /** The primary key: {@code contest} holds the contest's id. */
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID contest;
        private char label;
    }
}
