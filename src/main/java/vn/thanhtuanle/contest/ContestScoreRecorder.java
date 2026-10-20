package vn.thanhtuanle.contest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.problem.ProcessedVerdictRepository;

/**
 * Keeps {@code t_contest_scores} from verdict events, inside the statistics' transaction
 * ({@code ProblemStatsRecorder}), which has already written the submission's {@code t_processed_verdicts} row with its
 * current verdict. A contest verdict puts its contest, submitter, problem and submit time on that row, and the
 * submitter's score on the problem is then recomputed from all such rows. So a duplicate changes nothing, the order of
 * delivery does not matter, and a rejudge is one more recompute.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContestScoreRecorder {

    private final ContestScoreRepository scores;
    private final ProcessedVerdictRepository processedVerdicts;

    @Transactional(propagation = Propagation.MANDATORY)
    public void onVerdict(SubmissionVerdictRecorded e) {
        if (e.contestId() == null || e.userId() == null || e.submittedAt() == null) {
            log.debug("Verdict of submission {} keeps no contest score (contest {}, user {}, submitted at {})",
                    e.submissionId(), e.contestId(), e.userId(), e.submittedAt());
            return;
        }
        // Holds the contest until this transaction ends, so the score row's foreign key cannot fail: a delete in
        // flight is waited for, and its contest then reads as gone.
        if (!scores.lockContest(e.contestId())) {
            log.debug("Verdict of submission {} keeps no contest score: no contest {}", e.submissionId(), e.contestId());
            return;
        }
        processedVerdicts.attachContest(e.submissionId(), e.contestId(), e.userId(), e.problemId(), e.submittedAt());
        scores.recompute(e.contestId(), e.userId(), e.problemId());
    }
}
