package vn.thanhtuanle.contest.enrollment;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** A user's registration for a contest (contest enrollment design §4.1): one row per (contest, user). */
@Entity
@Table(name = "t_contest_enrollments")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Enrollment {

    @EmbeddedId
    private Key key;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "contest_id", nullable = false)
        private UUID contestId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;
    }
}
