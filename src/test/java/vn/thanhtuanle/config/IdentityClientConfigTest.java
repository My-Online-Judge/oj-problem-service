package vn.thanhtuanle.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import vn.thanhtuanle.oj.common.identity.OjIdentityClientProperties;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoreboard reads usernames through oj-common's PublicUserClient, which exists only when
 * {@code oj.identity.base-url} is set. application.yml points it at identity over plain HTTP; the tls profile, like
 * the JWKS fetch, at https://identity-service:8000 with this service's certificate (bundle {@code oj}).
 */
class IdentityClientConfigTest {

    private static OjIdentityClientProperties bind(String... files) throws IOException {
        List<PropertySource<?>> sources = new ArrayList<>();
        for (String file : files) {
            // the later file wins, as a profile's file wins over application.yml
            sources.addAll(0, new YamlPropertySourceLoader().load(file, new ClassPathResource(file)));
        }
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("oj.identity", OjIdentityClientProperties.class)
                .orElseThrow(() -> new AssertionError("oj.identity is not set"));
    }

    @Test
    void identityIsCalledOverHttpByDefault() throws IOException {
        OjIdentityClientProperties identity = bind("application.yml");

        assertThat(identity.baseUrl()).isEqualTo("http://identity-service:8000");
        assertThat(identity.sslBundle()).isNull();
        assertThat(identity.cacheTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theTlsProfileCallsIdentityOverMutualTls() throws IOException {
        OjIdentityClientProperties identity = bind("application.yml", "application-tls.yml");

        assertThat(identity.baseUrl()).isEqualTo("https://identity-service:8000");
        assertThat(identity.sslBundle()).isEqualTo("oj");
    }
}
