package vn.thanhtuanle.problem;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.event.OjTopics;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * A database that is briefly away is not poison: the verdict is retried until it is counted, not dead-lettered
 * after two seconds. (Three failures used to exhaust the old 1 s x 2 policy.)
 */
@EmbeddedKafka(partitions = 1, topics = {OjTopics.SUBMISSION_EVENTS, OjTopics.SUBMISSION_EVENTS_DLQ})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"})
class ProblemStatsConsumerRetryTest extends PostgresTest {

    @MockitoSpyBean ProblemStatsRecorder recorder;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

    @Test
    void aVerdictIsRetriedThroughABriefDatabaseOutageAndCounted() throws Exception {
        UUID problemId = problems.save(Problem.builder().title("retry").problemSlug("retry-" + UUID.randomUUID())
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build()).getId();
        TransientDataAccessResourceException blip = new TransientDataAccessResourceException("problem-db restarting");
        doThrow(blip).doThrow(blip).doThrow(blip).doCallRealMethod().when(recorder).record(any());

        kafka.send(OjTopics.SUBMISSION_EVENTS, problemId.toString(), objectMapper.writeValueAsString(
                new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0).toEnvelope()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(submission_count), 0) FROM t_problem_stats WHERE problem_id = ?",
                Long.class, problemId)).isEqualTo(1L));
        verify(recorder, atLeast(4)).record(any());
        assertThat(meters.counter("oj.stats.dead.lettered").count()).isZero();
    }
}
