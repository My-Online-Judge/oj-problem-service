package vn.thanhtuanle.problem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.contest.ContestScoreRecorder;
import vn.thanhtuanle.entity.ProcessedVerdict;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Adds a terminal verdict to its problem's statistics, once per submission: the events arrive at least
 * once (outbox relay retries, consumer re-deliveries), so the submission id is recorded in the same
 * transaction as the count, with the verdict it is counted under and its generation ({@code rejudgeCount}). A rejudge
 * moves the submission's count from that verdict to the new one, unless it is of an older generation than the one
 * counted: two verdicts of one submission can arrive in either order (two outbox relay passes send disjoint batches at
 * once, a dead letter is replayed after a newer rejudge), and the newest must stay counted. A verdict without a
 * generation (a producer older than oj-common 0.10.0) cannot be ordered and is applied as it arrives. Every verdict of a
 * known problem then goes on, in the same transaction, to the contest scores ({@link ContestScoreRecorder}):
 * duplicates, unchanged and older rejudges too, since a score is recomputed from the verdicts counted here and so can be
 * recomputed any number of times.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProblemStatsRecorder {

    private final ProblemRepository problemRepository;
    private final ProblemStatRepository problemStatRepository;
    private final ProcessedVerdictRepository processedVerdictRepository;
    private final ContestScoreRecorder contestScores;

    /**
     * @return true when the counts changed; false for a duplicate, a rejudge that changes nothing, a rejudge of an
     *         older generation than the one counted, or a problem this service does not know
     */
    @Transactional
    public boolean record(SubmissionVerdictRecorded event) {
        if (!problemRepository.existsById(event.problemId())) {
            log.warn("Verdict of submission {} skipped: unknown problem {}", event.submissionId(), event.problemId());
            return false;
        }
        boolean counted = event.rejudge() ? recordRejudge(event) : recordFirst(event);
        contestScores.onVerdict(event);
        return counted;
    }

    private boolean recordFirst(SubmissionVerdictRecorded event) {
        if (processedVerdictRepository.markProcessed(event.submissionId(), LocalDateTime.now(), event.verdict(),
                event.rejudgeCount()) == 0) {
            log.debug("Verdict of submission {} already counted", event.submissionId());
            return false;
        }
        problemStatRepository.increment(event.problemId(), event.verdict());
        return true;
    }

    /** Under the submission's row lock, so that two verdicts of one submission cannot both read the same previous one. */
    private boolean recordRejudge(SubmissionVerdictRecorded event) {
        Optional<ProcessedVerdict> row = processedVerdictRepository.findForUpdateBySubmissionId(event.submissionId());
        if (row.isEmpty()) {
            if (recordFirst(event)) {
                return true;
            }
            // Its first verdict was inserted meanwhile by another transaction, which the insert waited for.
            row = processedVerdictRepository.findForUpdateBySubmissionId(event.submissionId());
        }
        ProcessedVerdict processed = row.orElseThrow(() -> new IllegalStateException(
                "Processed verdict of submission " + event.submissionId() + " neither inserted nor found"));
        Integer generation = event.rejudgeCount();
        Integer counted = processed.getLastGeneration();
        if (generation != null && counted != null && generation < counted) {
            log.info("Rejudge of submission {} ({}) skipped: generation {} is older than the counted generation {}",
                    event.submissionId(), event.verdict(), generation, counted);
            return false;
        }
        processed.setProcessedAt(LocalDateTime.now());
        if (generation != null) {
            // Not older than the counted one (or none counted yet): from now on it is the newest applied.
            processed.setLastGeneration(generation);
        }
        Integer previous = processed.getLastStatus();
        if (previous == null) {
            // Counted before V5 under a verdict nobody recorded: moving "from" the new verdict could count it twice,
            // so the counts stay as they are and the row stays unknown for every later rejudge too.
            log.warn("Rejudge of submission {} ({}): previous verdict unknown, statistics left unchanged",
                    event.submissionId(), event.verdict());
            return false;
        }
        if (previous == event.verdict()) {
            log.debug("Rejudge of submission {} kept verdict {}", event.submissionId(), previous);
            return false;
        }
        problemStatRepository.decrement(event.problemId(), previous);
        problemStatRepository.increment(event.problemId(), event.verdict());
        processed.setLastStatus(event.verdict());
        return true;
    }
}
