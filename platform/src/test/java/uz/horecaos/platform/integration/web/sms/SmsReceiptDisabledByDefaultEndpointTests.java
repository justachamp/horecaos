package uz.horecaos.platform.integration.web.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The receipt endpoint as it ships (ADR 0146 Decision 4): no provider type has a
 * receipt source until a deployment names one, so VAS answers 404 and no receipt is
 * read for it by any means, however well formed the callback or however real the
 * attempt it names.
 *
 * <p>That includes the case the whole record turns on. VAS's own example shows the
 * callback's {@code key} arriving empty, so there is nothing in a real-looking VAS
 * body that proves who sent it, and an endpoint that accepted one by default would
 * be a way for anyone who guessed an installation id to mark any message delivered,
 * failed or blacklisted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmsReceiptDisabledByDefaultEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-5000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018f9b20-5000-7000-8000-0000000000d2");
    private static final UUID VAS_INSTALLATION = UUID.fromString("018f9b20-5000-7000-8000-0000000000d3");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        // horecaos.sms.receipts.enabled-provider-types is deliberately not set.
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private SmsReceiptFixture fixture;
    private UUID attempt;

    @BeforeEach
    void seed() {
        fixture = new SmsReceiptFixture(jdbc);
        fixture.truncate();
        fixture.tenantWithBrand(TENANT, BRAND, "receipts-default");
        fixture.environment("sms-vas-env", "SMSGW_VAS");
        UUID binding =
                fixture.installation(VAS_INSTALLATION, TENANT, BRAND, "SMSGW_VAS", "sms-vas-env", "ACTIVE", null);
        attempt = fixture.acceptedAttempt(TENANT, BRAND, binding, "SMSGW_VAS", "555555", null, Instant.now());
    }

    @Test
    @DisplayName("VAS answers 404 for the documented callback, and the attempt is left exactly as the send answered")
    void vasHasNoReceiptSourceByDefault() throws Exception {
        var response = mvc.perform(post("/providers/sms/" + VAS_INSTALLATION + "/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "login": "admin", "key": "", "id": 555555, "code": 4, "description": "DLVRD" }"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as("404, not 401: the path is permitAll and the refusal is the endpoint's own")
                .isEqualTo(404);
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("ACCEPTED");
        assertThat(fixture.eventStatuses(attempt)).containsExactly("ACCEPTED");
        assertThat(fixture.count("integration.inbox_messages")).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
