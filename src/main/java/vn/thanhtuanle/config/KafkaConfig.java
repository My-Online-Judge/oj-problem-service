package vn.thanhtuanle.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import vn.thanhtuanle.oj.common.event.OjTopics;

/**
 * The verdict topic is declared here as well as in submission-service, with the same partition count: whichever
 * service starts first creates it, instead of broker auto-creation making a single partition.
 *
 * <p>Failures are told apart. A record that can never be read (not JSON, another version, incomplete) goes
 * straight to the dead-letter topic, so one poison record cannot stall the statistics. Anything else — the
 * database restarting, a lock — is retried with a growing pause (1 s doubling to 30 s, about 5.5 minutes in
 * all) before it is dead-lettered too: a brief outage must not lose a count. Every dead-lettered record
 * increments {@code oj_stats_dead_lettered_total} (alert ProblemStatsDeadLettered).
 */
@Configuration
public class KafkaConfig {

    // Default 6 partitions; override with KAFKA_PARTITIONS. RF=1 (single-broker dev / self-host).
    @Value("${KAFKA_PARTITIONS:6}")
    private int partitions;

    @Bean
    public NewTopic submissionEventsTopic() {
        return TopicBuilder.name(OjTopics.SUBMISSION_EVENTS).partitions(partitions).replicas(1).build();
    }

    @Bean
    public NewTopic submissionEventsDlqTopic() {
        return TopicBuilder.name(OjTopics.SUBMISSION_EVENTS_DLQ).partitions(partitions).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meters) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(OjTopics.SUBMISSION_EVENTS_DLQ, record.partition()));
        Counter deadLettered = Counter.builder("oj.stats.dead.lettered")
                .description("Records of oj.submission.events the statistics consumer gave up on (now on the DLQ)")
                .register(meters);
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(15);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            deadLettered.increment();
            deadLetters.accept(record, ex);
        }, backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }
}
