package vn.thanhtuanle.problem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;

import java.time.LocalDateTime;

/**
 * Adds a terminal verdict to its problem's statistics, once per submission: the events arrive at least
 * once (outbox relay retries, consumer re-deliveries), so the submission id is recorded in the same
 * transaction as the count.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProblemStatsRecorder {

    private final ProblemRepository problemRepository;
    private final ProblemStatRepository problemStatRepository;
    private final ProcessedVerdictRepository processedVerdictRepository;

    /** @return true when the verdict was counted, false for a duplicate or a problem this service does not know */
    @Transactional
    public boolean record(SubmissionVerdictRecorded event) {
        if (!problemRepository.existsById(event.problemId())) {
            log.warn("Verdict of submission {} skipped: unknown problem {}", event.submissionId(), event.problemId());
            return false;
        }
        if (processedVerdictRepository.markProcessed(event.submissionId(), LocalDateTime.now()) == 0) {
            log.debug("Verdict of submission {} already counted", event.submissionId());
            return false;
        }
        problemStatRepository.increment(event.problemId(), event.verdict());
        return true;
    }
}
