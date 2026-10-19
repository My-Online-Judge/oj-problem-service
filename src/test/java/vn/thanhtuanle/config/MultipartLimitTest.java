package vn.thanhtuanle.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.yaml.snakeyaml.Yaml;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-file-size=1KB")
@ActiveProfiles("test")
@Import(MultipartLimitTest.TestKeyDecoder.class)
class MultipartLimitTest {

    static final RSAKey KEY = generateKey();

    @TestConfiguration
    static class TestKeyDecoder {
        @Bean
        JwtDecoder testJwtDecoder() throws JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(OjJwtDecoders.validator());
            return decoder;
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("multipart-test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String adminToken() throws JOSEException {
        Instant exp = Instant.now().plusSeconds(600);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("multipart-test")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(exp.minusSeconds(900)))
                .expirationTime(Date.from(exp))
                .claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString())
                .claim(OjJwtAuthenticationFilter.AUTHORITIES_CLAIM, List.of("problem:update"))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shippedLimits() throws Exception {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> servlet = (Map<String, Object>) spring.get("servlet");
            Map<String, Object> multipart = (Map<String, Object>) servlet.get("multipart");
            assertThat(multipart.get("max-file-size")).isEqualTo("50MB");
            assertThat(multipart.get("max-request-size")).isEqualTo("55MB");
        }
    }

    @Test
    void oversizeUploadAnswers413() throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(new byte[2048]) {
            @Override
            public String getFilename() {
                return "cases.zip";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(adminToken());

        ResponseEntity<Map> response = rest.exchange(
                "http://localhost:" + port + "/api/v1/problems/some-slug/test-cases/import",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).containsEntry("status", 413);
    }
}
