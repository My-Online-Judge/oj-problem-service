package vn.thanhtuanle.problem;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import vn.thanhtuanle.common.enums.ProblemStatus;
import vn.thanhtuanle.entity.Problem;
import vn.thanhtuanle.oj.common.event.OjTopics;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The listener end to end: what judge-api's outbox puts on oj.submission.events, and what it cannot read. */
@EmbeddedKafka(partitions = 1, topics = {OjTopics.SUBMISSION_EVENTS, OjTopics.SUBMISSION_EVENTS_DLQ})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"})
class ProblemStatsConsumerKafkaTest extends PostgresTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

    @Test
    void aVerdictEventFromTheOutboxIsCounted() throws Exception {
        UUID problemId = problems.save(Problem.builder().title("kafka").problemSlug("kafka-" + UUID.randomUUID())
                .status(ProblemStatus.ACTIVE.getValue()).timeLimit(1000).memoryLimit(256L).build()).getId();
        String json = objectMapper.writeValueAsString(
                new SubmissionVerdictRecorded(UUID.randomUUID(), problemId, 0).toEnvelope());

        kafka.send(OjTopics.SUBMISSION_EVENTS, problemId.toString(), json);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(submission_count), 0) FROM t_problem_stats WHERE problem_id = ?",
                Long.class, problemId)).isEqualTo(1L));
    }

    @Test
    void aRecordThatIsNotAnEventGoesToTheDeadLetterTopic() {
        String key = "poison-" + UUID.randomUUID();
        kafka.send(OjTopics.SUBMISSION_EVENTS, key, "{not json");

        try (Consumer<String, String> dlq = new DefaultKafkaConsumerFactory<String, String>(
                KafkaTestUtils.consumerProps("dlq-reader-" + UUID.randomUUID(), "true", broker),
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(dlq, OjTopics.SUBMISSION_EVENTS_DLQ);
            ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(dlq, OjTopics.SUBMISSION_EVENTS_DLQ,
                    Duration.ofSeconds(20));
            assertThat(dead.key()).isEqualTo(key);
            assertThat(dead.value()).isEqualTo("{not json");
        }
        assertThat(meters.counter("oj.stats.dead.lettered").count()).isGreaterThanOrEqualTo(1.0);
    }
}
