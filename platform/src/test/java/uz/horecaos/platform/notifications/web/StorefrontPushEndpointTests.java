package uz.horecaos.platform.notifications.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.integration.camel.notification.push.FakeBrowser;
import uz.horecaos.platform.integration.camel.notification.push.WebPushEncryptionAccess;
import uz.horecaos.platform.integration.web.sms.SmsReceiptFixture;
import uz.horecaos.platform.notifications.application.PushClickService;
import uz.horecaos.platform.notifications.application.PushSubscriptionService;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0182, D14, over HTTP, the way the storefront's service worker and a customer's browser reach it.
 *
 * <p>{@code CampaignPushDeliveryTests} proves what a subscription and a click do through the services.
 * This proves the things only the web layer can break: that the opt-in is the customer's own (no token
 * is the filter chain's 401, a token with no account here is a 404 and not a 403), that the browser's
 * own {@code PushSubscription.toJSON()} is accepted as it is (an {@code expirationTime} that is absent
 * or null among them), that a refusal says why without repeating the URL, and that the click callback
 * is reachable with no session at all and answers every unknown token alike.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontPushEndpointTests {

    private static final String ISSUER = "https://issuer.test/realms/horecaos";
    private static final UUID TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b1");
    private static final String CUSTOMER = "push-customer";
    private static final String STRANGER = "push-never-signed-up";
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/";
    private static final String UNKNOWN_BUT_WELL_FORMED = "A".repeat(32);
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String VAPID_PUBLIC;
    private static final String VAPID_PRIVATE;

    static {
        KeyPair vapid = WebPushEncryptionAccess.generateKeyPair();
        VAPID_PUBLIC = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(WebPushEncryptionAccess.uncompressed((ECPublicKey) vapid.getPublic()));
        VAPID_PRIVATE = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(WebPushEncryptionAccess.fixed(((ECPrivateKey) vapid.getPrivate()).getS()));
    }

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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add("horecaos.push.vapid.public-key", () -> VAPID_PUBLIC);
        registry.add(
                "horecaos.push.vapid.private-key-reference",
                () -> "horecaos:test:provider_notification:platform:vapid");
        registry.add("horecaos.push.vapid.subject", () -> "mailto:push@horecaos.uz");
        registry.add("horecaos.secrets.provider_notification.platform.vapid", () -> VAPID_PRIVATE);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ConsentService consents;

    @Autowired
    private PushClickService clicks;

    @Autowired
    private PushSubscriptionService subscriptions;

    @Autowired
    private JdbcNotificationStore notifications;

    private UUID account;
    private final FakeBrowser browser = new FakeBrowser();

    @BeforeEach
    void seed() {
        SmsReceiptFixture fixture = new SmsReceiptFixture(jdbc);
        fixture.truncate();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE notifications.push_click_tokens, notifications.push_subscriptions CASCADE")
                .update();
        fixture.tenantWithBrand(TENANT, BRAND, "push-endpoints");
        account = fixture.customerAccount(TENANT);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id, identity_partition_brand_id,
                    customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :t, NULL, :account, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("account", account)
                .param("issuer", ISSUER)
                .param("subject", CUSTOMER)
                .param("now", now)
                .update();
    }

    @Test
    @DisplayName("no token is the filter chain's 401, and a token with no account here is a 404, not a 403")
    void theOptInIsTheCustomersOwn() throws Exception {
        assertThat(mvc.perform(get(path())).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(put(path() + "/subscription")
                                .header("Idempotency-Key", "anonymous")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(subscription(FCM + "a")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        assertThat(mvc.perform(get(path()).with(token(STRANGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(put(path() + "/subscription")
                                .with(token(STRANGER))
                                .header("Idempotency-Key", "stranger")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(subscription(FCM + "a")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(liveSubscriptions()).as("neither left a subscription behind").isZero();
    }

    @Test
    @DisplayName("the status offers push once the platform can send it, with the key to subscribe under")
    void theStatusOffersPush() throws Exception {
        MvcResult status = mvc.perform(get(path()).with(token(CUSTOMER))).andReturn();

        assertThat(status.getResponse().getStatus()).isEqualTo(200);
        var body = JSON.readTree(status.getResponse().getContentAsString());
        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("publicKey").asString()).isEqualTo(VAPID_PUBLIC);
        assertThat(body.path("subscribed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the browser's own subscription, with or without an expiry, turns push on and records the consent")
    void subscribingTurnsPushOnAndRecordsConsent() throws Exception {
        assertThat(subscribe("first", subscription(FCM + "a"))).isEqualTo(204);
        assertThat(subscribed()).isTrue();
        assertThat(consentGranted())
                .as("the opt-in is the consent, on the PUSH channel")
                .isTrue();
        assertThat(consents.consentFor(TENANT, account, BRAND, "MARKETING_PROMOTIONS", "SMS"))
                .as("and on that channel only")
                .isEmpty();

        // PushSubscription.toJSON() with no expirationTime at all: Jackson 3 refuses a missing primitive, and
        // the field is a box.
        String withoutExpiry = "{\"endpoint\":\"%s\",\"keys\":{\"p256dh\":\"%s\",\"auth\":\"%s\"}}"
                .formatted(FCM + "b", browser.p256dh(), browser.auth());
        assertThat(subscribe("second", withoutExpiry)).isEqualTo(204);
        String nullExpiry = "{\"endpoint\":\"%s\",\"expirationTime\":null,\"keys\":{\"p256dh\":\"%s\",\"auth\":\"%s\"}}"
                .formatted(FCM + "c", browser.p256dh(), browser.auth());
        assertThat(subscribe("third", nullExpiry)).isEqualTo(204);
        assertThat(liveSubscriptions()).as("each replaced the last").isEqualTo(1);
    }

    @Test
    @DisplayName("a retried request under one key is one subscription; no key is a 400")
    void aRetryIsOneSubscription() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(subscribe("retry", subscription(FCM + "a"))).isEqualTo(204);
        }
        assertThat(liveSubscriptions()).isEqualTo(1);

        assertThat(mvc.perform(put(path() + "/subscription")
                                .with(token(CUSTOMER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(subscription(FCM + "a")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("no Idempotency-Key")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a subscription the platform will not send to is a 400 with a reason, and the URL is not repeated")
    void refusalsSayWhyWithoutTheUrl() throws Exception {
        MvcResult refused = mvc.perform(put(path() + "/subscription")
                        .with(token(CUSTOMER))
                        .header("Idempotency-Key", "ssrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subscription("https://internal.example/secret-device-token")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("PUSH_ENDPOINT_NOT_ALLOWED")
                .doesNotContain("internal.example")
                .doesNotContain("secret-device-token");

        assertThat(subscribe("http", subscription("http://fcm.googleapis.com/fcm/send/a")))
                .isEqualTo(400);
        assertThat(subscribe(
                        "keys",
                        "{\"endpoint\":\"%s\",\"keys\":{\"p256dh\":\"AAAA\",\"auth\":\"%s\"}}"
                                .formatted(FCM + "a", browser.auth())))
                .isEqualTo(400);
        assertThat(subscribe("nokeys", "{\"endpoint\":\"%s\"}".formatted(FCM + "a")))
                .isEqualTo(400);
        assertThat(liveSubscriptions()).isZero();
        assertThat(consentGranted())
                .as("a refused subscription records no consent")
                .isFalse();
    }

    @Test
    @DisplayName("turning push off ends the subscription and withdraws the consent, and is the same answer twice")
    void turningOffEndsBoth() throws Exception {
        subscribe("on", subscription(FCM + "a"));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(mvc.perform(delete(path() + "/subscription")
                                    .with(token(CUSTOMER))
                                    .header("Idempotency-Key", "off-" + attempt))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(204);
        }

        assertThat(subscribed()).isFalse();
        assertThat(consentGranted()).isFalse();
    }

    @Test
    @DisplayName("the service worker's report of a tap is reachable with no session, and is the read")
    void aTapIsReportedWithNoSession() throws Exception {
        String token = acceptedPushMessage();

        MvcResult tapped = mvc.perform(post("/providers/push/clicks/" + token).with(from("10.2.0.1")))
                .andReturn();

        assertThat(tapped.getResponse().getStatus())
                .as("permitAll on the filter chain: the worker holds no session, no JWT and no CSRF token")
                .isEqualTo(204);
        assertThat(tapped.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(jdbc.sql("SELECT status FROM notifications.delivery_attempts")
                        .query(String.class)
                        .single())
                .as("a tap proves the message reached the browser")
                .isEqualTo("DELIVERED");
        assertThat(jdbc.sql("SELECT normalized_status FROM notifications.delivery_status_events")
                        .query(String.class)
                        .list())
                .containsExactly("READ");

        assertThat(mvc.perform(post("/providers/push/clicks/" + token).with(from("10.2.0.1")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("tapping again is the same fact, not an error")
                .isEqualTo(204);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications.delivery_status_events")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown token and a malformed one are one answer, and a GET is not a tap")
    void unknownAndMalformedTokensAreOneAnswer() throws Exception {
        for (String token :
                new String[] {UNKNOWN_BUT_WELL_FORMED, "x", "A".repeat(65), "not.a.token.at.all.or.that.long"}) {
            assertThat(mvc.perform(post("/providers/push/clicks/" + token).with(from("10.2.0.3")))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as("«%s»", token)
                    .isEqualTo(404);
        }
        String token = acceptedPushMessage();
        assertThat(mvc.perform(get("/providers/push/clicks/" + token).with(from("10.2.0.3")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a link scanner's GET reads nothing and changes nothing")
                .isIn(401, 405);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications.delivery_status_events")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("one caller cannot enumerate tokens: the endpoint is rate limited")
    void clicksAreRateLimited() throws Exception {
        int first = 0;
        boolean limited = false;
        for (int i = 0; i < 200 && !limited; i++) {
            MvcResult result = mvc.perform(post("/providers/push/clicks/" + UNKNOWN_BUT_WELL_FORMED)
                            .with(from("10.2.0.4")))
                    .andReturn();
            if (i == 0) {
                first = result.getResponse().getStatus();
            }
            limited = result.getResponse().getStatus() == 429;
        }
        assertThat(first).as("the first call is answered").isEqualTo(404);
        assertThat(limited).as("and a flood from one address is refused").isTrue();
    }

    // ---------------------------------------------------------------- fixtures

    /** A message the push service accepted, with the click token that was put in its payload. */
    private String acceptedPushMessage() {
        UUID notificationId = UUID.randomUUID();
        notifications.createIntent(new NewNotification(
                notificationId,
                TENANT,
                BRAND,
                null,
                "MARKETING",
                "PUSH",
                "PROMO_PUSH",
                "MarketingCampaign",
                UUID.randomUUID(),
                account,
                null,
                "push-endpoint-" + notificationId,
                "{}",
                NOW,
                NOW.plusSeconds(3600),
                NOW,
                null));
        UUID attemptId = UUID.randomUUID();
        notifications.insertAttempt(attemptId, TENANT, notificationId, "PUSH", null, "WEB_PUSH", 1, "key-1", NOW);
        notifications.settleAttempt(TENANT, attemptId, "ACCEPTED", "topic", null, null, "WEB_PUSH", null, NOW);
        return clicks.issue(TENANT, notificationId, java.time.Duration.ofHours(12));
    }

    private int subscribe(String key, String body) throws Exception {
        return mvc.perform(put(path() + "/subscription")
                        .with(token(CUSTOMER))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String subscription(String endpoint) {
        return "{\"endpoint\":\"%s\",\"expirationTime\":null,\"keys\":{\"p256dh\":\"%s\",\"auth\":\"%s\"}}"
                .formatted(endpoint, browser.p256dh(), browser.auth());
    }

    private boolean subscribed() {
        return subscriptions.subscribed(TENANT, BRAND, account);
    }

    private boolean consentGranted() {
        return consents.consentFor(TENANT, account, BRAND, "MARKETING_PROMOTIONS", "PUSH")
                .map(state -> state.granted())
                .orElse(false);
    }

    private int liveSubscriptions() {
        return jdbc.sql("SELECT COUNT(*) FROM notifications.push_subscriptions WHERE status = 'ACTIVE'")
                .query(Integer.class)
                .single();
    }

    private static String path() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/push";
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
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
