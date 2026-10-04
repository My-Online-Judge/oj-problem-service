package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.support.PostgresTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Counting a verdict: once per submission, only for problems this service knows. */
@Transactional
class ProblemStatsRecorderTest extends PostgresTest {

    @Autowired ProblemStatsRecorder recorder;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;

    private UUID problem() {
        return problems.save(Problem.builder().title("rec").problemSlug("rec-" + UUID.randomUUID())
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build()).getId();
    }

    private Long count(UUID problemId, int verdict) {
        return jdbc.query("SELECT submission_count FROM t_problem_stats WHERE problem_id = ? AND verdict = ?",
                rs -> rs.next() ? rs.getLong(1) : null, problemId, verdict);
    }

    @Test
    void theSameSubmissionDeliveredTwiceCountsOnce() {
        UUID problemId = problem();
        var event = new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0);

        assertThat(recorder.record(event)).isTrue();
        assertThat(recorder.record(event)).isFalse();

        assertThat(count(problemId, 0)).isEqualTo(1L);
    }

    @Test
    void submissionsAddUpPerVerdict() {
        UUID problemId = problem();
        recorder.record(new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0));
        recorder.record(new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0));
        recorder.record(new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, -1));

        assertThat(count(problemId, 0)).isEqualTo(2L);
        assertThat(count(problemId, -1)).isEqualTo(1L);
    }

    @Test
    void aVerdictForAnUnknownProblemIsSkippedAndNotMarkedProcessed() {
        UUID submissionId = UUID.randomUUID();

        assertThat(recorder.record(new SubmissionVerdictRecorded(submissionId, UUID.randomUUID(), 0))).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_processed_verdicts WHERE submission_id = ?",
                Integer.class, submissionId)).isZero();
    }
}
