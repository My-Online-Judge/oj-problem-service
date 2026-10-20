package vn.thanhtuanle.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A package export streams asynchronously, and Tomcat's default async timeout (30 s) would cut a large one off; the
 * portal waits 120 s. The shipped application.yml binds Spring MVC's async timeout to 120 s.
 */
class AsyncRequestTimeoutTest {

    @Test
    void asyncRequestsMayRunFor120Seconds() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));

        WebMvcProperties mvc = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.mvc", WebMvcProperties.class)
                .orElseGet(WebMvcProperties::new);

        assertThat(mvc.getAsync().getRequestTimeout()).isEqualTo(Duration.ofSeconds(120));
    }
}
