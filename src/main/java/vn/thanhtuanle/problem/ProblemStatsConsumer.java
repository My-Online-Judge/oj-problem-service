package vn.thanhtuanle.problem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.oj.common.event.OjTopics;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;

import java.io.IOException;

/**
 * Keeps the per-problem statistics from {@code oj.submission.events}. Other event types are ignored. A
 * record that is not a readable {@code SubmissionVerdictRecorded} v1 throws: after the error handler's
 * retries it lands on the dead-letter topic, kept for inspection and replay.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProblemStatsConsumer {

    public static final String GROUP_ID = "problem-service-stats";

    private final ObjectMapper objectMapper;
    private final ProblemStatsRecorder recorder;

    @KafkaListener(topics = OjTopics.SUBMISSION_EVENTS, groupId = GROUP_ID)
    public void onEvent(String json) throws IOException {
        JsonNode envelope = objectMapper.readTree(json);
        String type = envelope.path("eventType").asText();
        if (!SubmissionVerdictRecorded.TYPE.equals(type)) {
            log.debug("Ignoring event of type {}", type);
            return;
        }
        int version = envelope.path("version").asInt();
        if (version != SubmissionVerdictRecorded.VERSION) {
            throw new IllegalArgumentException("Unsupported " + type + " version " + version);
        }
        SubmissionVerdictRecorded event = objectMapper.treeToValue(envelope.get("payload"),
                SubmissionVerdictRecorded.class);
        if (event == null || event.submissionId() == null || event.problemId() == null) {
            throw new IllegalArgumentException("Incomplete " + type + ": " + json);
        }
        if (event.verdict() == SubmissionResult.PENDING.getValue()
                || event.verdict() == SubmissionResult.JUDGING.getValue()) {
            throw new IllegalArgumentException("Not a terminal verdict: " + json);
        }
        recorder.record(event);
    }
}
