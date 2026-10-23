package uz.horecaos.platform.notifications.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.integration.web.sms.SmsReceiptFixture;
import uz.horecaos.platform.notifications.application.EmailUnsubscribeService;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0182, D13, over HTTP, the way a customer's mail client and a link scanner reach it: no
 * session, no CSRF token, no JWT.
 *
 * <p>{@code CampaignEmailDeliveryTests} proves what a token does through the service. This proves the
 * three things only the web layer can break: that the filter chain lets the pair through unauthenticated
 * (otherwise the mail client's button meets a bodyless 401 and the click becomes a spam complaint
 * against the sender every tenant shares), that a GET changes nothing (a scanner fetches every link in
 * a message), and that an unknown token and a malformed one are the same answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailUnsubscribeEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-6000-7000-8000-0000000000b1");
    private static final String PURPOSE = "PROMOTIONS";
    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");
    private static final String UNKNOWN_BUT_WELL_FORMED = "A".repeat(32);

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
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EmailUnsubscribeService unsubscribes;

    @Autowired
    private ConsentService consents;

    @Autowired
    private JdbcNotificationStore notifications;

    private UUID account;
    private String token;

    @BeforeEach
    void seed() {
        SmsReceiptFixture fixture = new SmsReceiptFixture(jdbc);
        fixture.truncate();
        fixture.tenantWithBrand(TENANT, BRAND, "unsubscribe-one");
        account = fixture.customerAccount(TENANT);
        consents.record(
                TENANT,
                account,
                BRAND,
                PURPOSE,
                "EMAIL",
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                "storefront-checkbox",
                NOW.minusSeconds(86_400));
        UUID notificationId = UUID.randomUUID();
        notifications.createIntent(new NewNotification(
                notificationId,
                TENANT,
                BRAND,
                null,
                "MARKETING",
                "EMAIL",
                "PROMO_EMAIL",
                "MarketingCampaign",
                UUID.randomUUID(),
                account,
                null,
                "unsubscribe-endpoint-" + notificationId,
                "{}",
                NOW,
                NOW.plusSeconds(3600),
                NOW,
                null));
        token = unsubscribes.issue(TENANT, BRAND, account, notificationId, PURPOSE, "ru");
    }

    @Test
    @DisplayName("a link scanner's GET shows the page and withdraws nothing")
    void aGetChangesNothing() throws Exception {
        MvcResult page = mvc.perform(get("/providers/mail/unsubscribe/" + token).with(request -> {
                    request.setRemoteAddr("10.1.0.1");
                    return request;
                }))
                .andReturn();

        assertThat(page.getResponse().getStatus())
                .as("permitAll on the filter chain: no session, no JWT")
                .isEqualTo(200);
        assertThat(page.getResponse().getContentType()).startsWith("text/html");
        assertThat(page.getResponse().getContentAsString())
                .as("the page names the brand and nothing about the customer")
                .contains("Brand")
                .doesNotContain(account.toString());
        assertThat(page.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(page.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(emailConsentGranted())
                .as("fetching a link is not a decision: a scanner fetches every URL in a message")
                .isTrue();
    }

    @Test
    @DisplayName("the mail client's one-click POST, with no session and no CSRF token, withdraws the EMAIL consent")
    void aOneClickPostWithdraws() throws Exception {
        MvcResult done = mvc.perform(post("/providers/mail/unsubscribe/" + token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click")
                        .with(request -> {
                            request.setRemoteAddr("10.1.0.2");
                            return request;
                        }))
                .andReturn();

        assertThat(done.getResponse().getStatus())
                .as("RFC 8058: the client posts with no credentials and must not meet a 401 or a 403")
                .isEqualTo(200);
        assertThat(emailConsentGranted()).isFalse();
        assertThat(consents.consentFor(TENANT, account, BRAND, PURPOSE, "SMS"))
                .as("a link in an email does not touch another channel")
                .isEmpty();

        MvcResult again = mvc.perform(post("/providers/mail/unsubscribe/" + token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click")
                        .with(request -> {
                            request.setRemoteAddr("10.1.0.2");
                            return request;
                        }))
                .andReturn();
        assertThat(again.getResponse().getStatus())
                .as("clicking again is the same act, not an error")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an unknown token and a malformed one are one answer, for a GET and for a POST")
    void unknownAndMalformedTokensAreOneAnswer() throws Exception {
        String unknown = unknownGet(UNKNOWN_BUT_WELL_FORMED);
        assertThat(unknownGet("x")).as("too short to be a token").isEqualTo(unknown);
        assertThat(unknownGet("A".repeat(65))).as("too long to be a token").isEqualTo(unknown);
        assertThat(unknownGet("not.a.token.at.all.or.that.long")).isEqualTo(unknown);

        MvcResult post = mvc.perform(post("/providers/mail/unsubscribe/" + UNKNOWN_BUT_WELL_FORMED)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click")
                        .with(request -> {
                            request.setRemoteAddr("10.1.0.3");
                            return request;
                        }))
                .andReturn();
        assertThat(post.getResponse().getStatus()).isEqualTo(404);
        assertThat(post.getResponse().getContentAsString()).isEqualTo(unknown);
        assertThat(emailConsentGranted())
                .as("a token that nothing minted withdraws nobody's consent")
                .isTrue();
    }

    @Test
    @DisplayName("one caller cannot enumerate tokens: the endpoint is rate limited")
    void callsAreRateLimited() throws Exception {
        int first = 0;
        boolean limited = false;
        for (int i = 0; i < 100 && !limited; i++) {
            MvcResult result = mvc.perform(get("/providers/mail/unsubscribe/" + UNKNOWN_BUT_WELL_FORMED)
                            .with(request -> {
                                request.setRemoteAddr("10.1.0.4");
                                return request;
                            }))
                    .andReturn();
            if (i == 0) {
                first = result.getResponse().getStatus();
            }
            limited = result.getResponse().getStatus() == 429;
        }
        assertThat(first).as("the first call is answered").isEqualTo(404);
        assertThat(limited).as("and a flood from one address is refused").isTrue();
    }

    private String unknownGet(String token) throws Exception {
        MvcResult result = mvc.perform(
                        get("/providers/mail/unsubscribe/" + token).with(request -> {
                            request.setRemoteAddr("10.1.0.3");
                            return request;
                        }))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        return result.getResponse().getContentAsString();
    }

    private boolean emailConsentGranted() {
        return consents.consentFor(TENANT, account, BRAND, PURPOSE, "EMAIL")
                .map(state -> state.granted())
                .orElse(false);
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
