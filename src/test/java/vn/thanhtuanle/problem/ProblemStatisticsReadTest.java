package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.entity.ProblemStat;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;
import vn.thanhtuanle.support.PostgresTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A problem's numbers are read from its verdict counts (t_problem_stats), filled by the verdict consumer.
 * On Postgres: these are native queries.
 */
@Transactional
class ProblemStatisticsReadTest extends PostgresTest {

    @Autowired ProblemRepository problems;
    @Autowired ProblemStatRepository stats;
    @Autowired ProblemService problemService;

    private Problem save(String slug) {
        return problems.save(Problem.builder().title("stats " + slug).problemSlug(slug)
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build());
    }

    @Test
    void theDetailShowsTotalsAndTheHistogramOfTerminalVerdicts() {
        Problem p = save("stats-" + UUID.randomUUID());
        stats.save(new ProblemStat(p.getId(), SubmissionResult.ACCEPTED.getValue(), 3));
        stats.save(new ProblemStat(p.getId(), SubmissionResult.WRONG_ANSWER.getValue(), 2));

        ProblemResponseDto dto = problemService.getProblemBySlug(p.getProblemSlug());

        assertThat(dto.getTotalSubmission()).isEqualTo(5);
        assertThat(dto.getAcceptedSubmission()).isEqualTo(3);
        assertThat(dto.getStatisticInfo())
                .containsEntry("0", 3)
                .containsEntry("-1", 2)
                .containsEntry("6", 0)   // PENDING: only terminal verdicts are counted
                .hasSize(SubmissionResult.values().length);
    }

    @Test
    void theListShowsTotalsAndZeroForAProblemNobodySubmitted() {
        String marker = "stats-list-" + UUID.randomUUID();
        Problem judged = save(marker + "-judged");
        save(marker + "-untouched");
        stats.save(new ProblemStat(judged.getId(), SubmissionResult.ACCEPTED.getValue(), 1));
        stats.save(new ProblemStat(judged.getId(), SubmissionResult.RUNTIME_ERROR.getValue(), 4));

        var page = problemService.getProblems(0, 10, marker, null, null);

        assertThat(page.getData())
                .extracting(ProblemResponseDto::getProblemSlug, ProblemResponseDto::getTotalSubmission,
                        ProblemResponseDto::getAcceptedSubmission)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(marker + "-judged", 5, 1),
                        org.assertj.core.groups.Tuple.tuple(marker + "-untouched", 0, 0));
    }
}
