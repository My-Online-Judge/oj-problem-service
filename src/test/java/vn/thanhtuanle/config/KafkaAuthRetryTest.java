package vn.thanhtuanle.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener that starts before its ACLs exist (on K3s the {@code kafka-init} Job can finish a minute after this pod
 * starts) gets TOPIC_AUTHORIZATION_FAILED. Without an auth-exception retry interval Spring Kafka stops that container
 * for good, and the per-problem statistics stop counting until the pod is restarted. The factory must keep retrying.
 */
class KafkaAuthRetryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            // KafkaConfig declares topics; without a broker KafkaAdmin would wait out its timeouts creating them
            .withPropertyValues("spring.kafka.admin.auto-create=false");

    @Test
    void theListenerContainerFactoryRetriesAfterAnAuthorizationFailure() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                    context.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(factory.getContainerProperties().getAuthExceptionRetryInterval())
                    .isNotNull().isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30));
        });
    }
}
