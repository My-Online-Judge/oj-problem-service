package vn.thanhtuanle.contest;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A contest runs over the half-open window [startsAt, endsAt). */
class ContestPhaseTest {

    private static final Instant START = Instant.parse("2026-10-21T02:00:00Z");
    private static final Instant END = Instant.parse("2026-10-21T05:00:00Z");

    @Test
    void beforeStartIsUpcoming() {
        assertThat(ContestPhase.of(START, END, START.minusNanos(1))).isEqualTo(ContestPhase.UPCOMING);
    }

    @Test
    void atStartIsRunning() {
        assertThat(ContestPhase.of(START, END, START)).isEqualTo(ContestPhase.RUNNING);
        assertThat(ContestPhase.of(START, END, END.minusNanos(1))).isEqualTo(ContestPhase.RUNNING);
    }

    @Test
    void atEndIsEnded() {
        assertThat(ContestPhase.of(START, END, END)).isEqualTo(ContestPhase.ENDED);
        assertThat(ContestPhase.of(START, END, END.plusSeconds(3600))).isEqualTo(ContestPhase.ENDED);
    }
}
