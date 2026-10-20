package vn.thanhtuanle.contest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.problem.ProcessedVerdictRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Keeps {@code t_contest_scores} from verdict events, inside the statistics' transaction
 * ({@code ProblemStatsRecorder}), which has already written the submission's {@code t_processed_verdicts} row with its
 * current verdict. A contest verdict puts its contest, submitter, problem and submit time on that row, and the
 * submitter's score on the problem is then recomputed from all such rows. So a duplicate changes nothing, the order of
 * delivery does not matter, and a rejudge is one more recompute, even one sent without its contest fields.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContestScoreRecorder {

    private final ContestScoreRepository scores;
    private final ProcessedVerdictRepository processedVerdicts;

    @Transactional(propagation = Propagation.MANDATORY)
    public void onVerdict(SubmissionVerdictRecorded e) {
        if (e.contestId() == null) {
            recomputeAttached(e.submissionId());
            return;
        }
        if (e.userId() == null || e.submittedAt() == null) {
            // Producers from 0.8.0 on always send both with a contest: a producer bug, not a practice verdict.
            log.warn("Contest verdict of submission {} keeps no score: no submitter ({}) or submit time ({}), contest {}",
                    e.submissionId(), e.userId(), e.submittedAt(), e.contestId());
            return;
        }
        // Locks the contest until this transaction ends: score writes in it take turns, and the score row's foreign
        // key cannot fail (a delete in flight is waited for, and its contest then reads as gone).
        if (!scores.lockContest(e.contestId())) {
            log.debug("Verdict of submission {} keeps no contest score: no contest {}", e.submissionId(), e.contestId());
            return;
        }
        processedVerdicts.attachContest(e.submissionId(), e.contestId(), e.userId(), e.problemId(), e.submittedAt());
        scores.recompute(e.contestId(), e.userId(), e.problemId());
    }

    /**
     * A verdict without contest fields: practice, or a producer from before 0.8.0 (submission-service rolled back)
     * sending a verdict of a submission an earlier event attached here. A rejudge among those has just changed the
     * row's verdict, so the score is recomputed from the row's own contest, submitter and problem.
     */
    private void recomputeAttached(UUID submissionId) {
        Optional<ProcessedVerdictRepository.ContestKey> attached = processedVerdicts.findContestKey(submissionId);
        if (attached.isEmpty()) {
            log.debug("Verdict of submission {} keeps no contest score: not a contest submission", submissionId);
            return;
        }
        ProcessedVerdictRepository.ContestKey key = attached.get();
        if (!scores.lockContest(key.getContestId())) {
            log.debug("Verdict of submission {} keeps no contest score: no contest {}", submissionId, key.getContestId());
            return;
        }
        processedVerdicts.flush(); // a rejudge's new last_status, still pending, before the plain-SQL recompute reads it
        scores.recompute(key.getContestId(), key.getUserId(), key.getProblemId());
    }
}
