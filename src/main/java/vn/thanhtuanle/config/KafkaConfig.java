package vn.thanhtuanle.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import vn.thanhtuanle.oj.common.event.OjTopics;

/**
 * The verdict topic is declared here as well as in judge-api, with the same partition count: whichever
 * service starts first creates it, instead of broker auto-creation making a single partition. Records the
 * consumer cannot process are retried twice, 1 s apart, then published to the dead-letter topic and
 * skipped, so one poison record cannot stall the statistics.
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
    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(OjTopics.SUBMISSION_EVENTS_DLQ, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }
}
