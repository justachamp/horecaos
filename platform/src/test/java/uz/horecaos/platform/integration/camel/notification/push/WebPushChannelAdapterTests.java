package uz.horecaos.platform.integration.camel.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.notification.AccountContext;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationDispatch.PushEnvelope;
import uz.horecaos.platform.notifications.api.PushEndpointPolicy;

/**
 * The Web Push adapter against a fake push service (ADR 0182, D14): what it puts on the wire, what it
 * makes of each answer, and what it refuses to do.
 *
 * <p>The fake is a real socket, reached through the real adapter, and plays the browser vendor's side of
 * RFC 8030: it records the request, and a fake browser holding the subscription's keys decrypts the
 * body. So the payload a customer's device would show is read here with the keys the subscription was
 * made with, and the VAPID token is verified with the public key the browser subscribed under.
 */
class WebPushChannelAdapterTests {

    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");
    private static final String SUBJECT = "mailto:push@horecaos.uz";
    private static final String SECRET_REFERENCE = "horecaos:test:provider_notification:platform:vapid";
    private static final ProviderCall CALL = new ProviderCall("", "", "key", Duration.ofSeconds(5));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FakePushService service;
    private FakeBrowser browser;
    private KeyPair vapid;
    private String vapidPublicKey;
    private WebPushChannelAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        service = FakePushService.start();
        browser = new FakeBrowser();
        vapid = WebPushEncryption.generateKeyPair();
        vapidPublicKey = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(WebPushEncryption.uncompressed((ECPublicKey) vapid.getPublic()));
        adapter = adapter(vapidPublicKey, SECRET_REFERENCE, SUBJECT, new PushEndpointPolicy("127.0.0.1", false));
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private WebPushChannelAdapter adapter(
            String publicKey, String reference, String subject, PushEndpointPolicy policy) {
        String privateKey = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(WebPushEncryption.fixed(((ECPrivateKey) vapid.getPrivate()).getS()));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.provider_notification.platform.vapid", privateKey)::get, clock);
        return new WebPushChannelAdapter(secrets, policy, JSON, clock, publicKey, reference, subject);
    }

    @Test
    @DisplayName("a push is encrypted to the browser, signed as the platform, and only accepted")
    void aPushReachesTheBrowserEncryptedAndSigned() throws Exception {
        ProviderOutcome outcome = adapter.send(dispatch(service.endpoint("/wpush/a"), "Aksiya", "Chegirma 20%"), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(outcome.normalized())
                .as("a push service that took the message has said nothing about a screen")
                .containsEntry("providerStatus", "PUSH_ACCEPTED")
                .containsEntry("normalizedStatus", "ACCEPTED");
        assertThat(outcome.externalReference()).isEqualTo(envelope().topic());

        var request = service.receivedAt("/wpush/a").getFirst();
        assertThat(request.header("content-encoding")).isEqualTo("aes128gcm");
        assertThat(request.header("ttl")).isEqualTo("3600");
        assertThat(request.header("urgency"))
                .as("a promotion does not wake a sleeping phone")
                .isEqualTo("low");
        assertThat(request.header("topic")).isEqualTo(envelope().topic());
        assertThat(service.vapidVerifies(request, (ECPublicKey) vapid.getPublic(), SUBJECT))
                .as("the push service can check who is pushing, with the key the browser subscribed under")
                .isTrue();
        assertThat(request.header("authorization")).endsWith(", k=" + vapidPublicKey);

        JsonNode payload = JSON.readTree(browser.read(request.body()));
        assertThat(payload.path("title").asString()).isEqualTo("Aksiya");
        assertThat(payload.path("body").asString()).isEqualTo("Chegirma 20%");
        assertThat(payload.path("path").asString()).isEqualTo("/menu");
        assertThat(payload.path("click").asString()).isEqualTo(envelope().clickUrl());
        assertThat(payload.path("image").asString()).isEqualTo(envelope().imageUrl());
        assertThat(payload.path("tag").asString()).isEqualTo(envelope().topic());
    }

    @Test
    @DisplayName("404 and 410 say the subscription is over, which is not a hard bounce")
    void aGoneSubscriptionIsRejectedWithoutBouncing() {
        service.respond("/wpush/gone", 410);
        service.respond("/wpush/unknown", 404);

        for (String path : new String[] {"/wpush/gone", "/wpush/unknown"}) {
            ProviderOutcome outcome = adapter.send(dispatch(service.endpoint(path), "Aksiya", "Matn"), CALL);

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo(WebPushChannelAdapter.SUBSCRIPTION_GONE);
            assertThat(outcome.normalized())
                    .as("a dead device is not a withdrawn consent: it must not suppress the customer")
                    .doesNotContainKey("hardBounce");
        }
    }

    @Test
    @DisplayName("a rate limit and a push service fault are retried, honouring the service's own wait")
    void rateLimitsAndFaultsAreRetried() {
        service.respondRetryAfter("/wpush/slow", 429, "120");
        service.respondRetryAfter("/wpush/forever", 429, "999999");
        service.respond("/wpush/down", 503);

        ProviderOutcome limited = adapter.send(dispatch(service.endpoint("/wpush/slow"), "A", "B"), CALL);
        assertThat(limited.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(limited.errorCode()).isEqualTo("PUSH_RATE_LIMITED");
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(120));

        assertThat(adapter.send(dispatch(service.endpoint("/wpush/forever"), "A", "B"), CALL)
                        .retryAfter())
                .as("a service cannot park a campaign for a week")
                .isEqualTo(Duration.ofHours(1));

        ProviderOutcome down = adapter.send(dispatch(service.endpoint("/wpush/down"), "A", "B"), CALL);
        assertThat(down.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(down.errorCode()).isEqualTo("PUSH_SERVICE_ERROR");
    }

    @Test
    @DisplayName("a refused VAPID identity, an oversize message, a bad request and a redirect are rejections")
    void refusalsAreRejections() {
        service.respond("/wpush/401", 401);
        service.respond("/wpush/403", 403);
        service.respond("/wpush/413", 413);
        service.respond("/wpush/400", 400);
        service.redirect("/wpush/moved", service.endpoint("/wpush/elsewhere"));

        assertThat(adapter.send(dispatch(service.endpoint("/wpush/401"), "A", "B"), CALL)
                        .errorCode())
                .isEqualTo("PUSH_VAPID_REJECTED");
        assertThat(adapter.send(dispatch(service.endpoint("/wpush/403"), "A", "B"), CALL)
                        .errorCode())
                .isEqualTo("PUSH_VAPID_REJECTED");
        assertThat(adapter.send(dispatch(service.endpoint("/wpush/413"), "A", "B"), CALL)
                        .errorCode())
                .isEqualTo("PUSH_PAYLOAD_TOO_LARGE");
        assertThat(adapter.send(dispatch(service.endpoint("/wpush/400"), "A", "B"), CALL)
                        .errorCode())
                .isEqualTo("PUSH_REQUEST_REFUSED");

        ProviderOutcome redirected = adapter.send(dispatch(service.endpoint("/wpush/moved"), "A", "B"), CALL);
        assertThat(redirected.errorCode()).isEqualTo("PUSH_REDIRECT_REFUSED");
        assertThat(service.receivedAt("/wpush/elsewhere"))
                .as("a redirect is the classic way past an allowlist of hosts, so it is never followed")
                .isEmpty();
    }

    @Test
    @DisplayName("a request that went out and was not answered is uncertain; one that never connected is retried")
    void noAnswerIsUncertainAndNoConnectionIsRetried() {
        service.hangUp("/wpush/silent");
        ProviderOutcome silent = adapter.send(dispatch(service.endpoint("/wpush/silent"), "A", "B"), CALL);
        assertThat(silent.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(silent.errorCode()).isEqualTo("PUSH_NO_ANSWER");

        String closed = service.endpoint("/wpush/after");
        service.close();
        ProviderOutcome unreachable = adapter.send(dispatch(closed, "A", "B"), CALL);
        assertThat(unreachable.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(unreachable.errorCode()).isEqualTo("PUSH_SERVICE_UNREACHABLE");
    }

    @Test
    @DisplayName("a subscription URL the platform does not send to is never called")
    void anAddressOutsideTheAllowlistIsNeverCalled() {
        WebPushChannelAdapter strict =
                adapter(vapidPublicKey, SECRET_REFERENCE, SUBJECT, new PushEndpointPolicy("fcm.googleapis.com", true));

        ProviderOutcome outcome = strict.send(dispatch(service.endpoint("/wpush/ssrf"), "A", "B"), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo(WebPushChannelAdapter.SUBSCRIPTION_GONE);
        assertThat(service.received())
                .as("the customer's browser chose that URL; the platform does not take it as an instruction")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing that identifies a device or a message is in an outcome")
    void outcomesCarryNoAddressAndNoText() {
        String endpoint = service.endpoint("/wpush/secret-device-token");
        service.respond("/wpush/secret-device-token", 410);

        ProviderOutcome gone = adapter.send(dispatch(endpoint, "Private title", "Private body"), CALL);

        assertThat(String.valueOf(gone))
                .doesNotContain("secret-device-token")
                .doesNotContain("127.0.0.1")
                .doesNotContain("Private");
    }

    @Test
    @DisplayName("it carries marketing only, and a campaign push without its click link or title is not sent")
    void marketingOnlyAndWhole() {
        NotificationDispatch transactional = new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "PUSH",
                service.endpoint("/wpush/a"),
                "Title",
                "Body",
                "key",
                "corr",
                "Order",
                UUID.randomUUID(),
                "ORDER_READY",
                NotificationDispatch.PURPOSE_TRANSACTIONAL,
                null,
                envelope());
        assertThat(adapter.send(transactional, CALL).errorCode()).isEqualTo("PUSH_PURPOSE_NOT_PERMITTED");
        assertThat(adapter.permits(NotificationDispatch.PURPOSE_COURIER, AccountContext.EMPTY))
                .isFalse();
        assertThat(adapter.permits(NotificationDispatch.PURPOSE_MARKETING, AccountContext.EMPTY))
                .isTrue();

        NotificationDispatch noEnvelope = new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "PUSH",
                service.endpoint("/wpush/a"),
                "Title",
                "Body",
                "key",
                "corr",
                "MarketingCampaign",
                UUID.randomUUID(),
                "PROMO",
                NotificationDispatch.PURPOSE_MARKETING,
                null,
                null);
        assertThat(adapter.send(noEnvelope, CALL).errorCode()).isEqualTo("PUSH_ENVELOPE_MISSING");

        NotificationDispatch noTitle = dispatch(service.endpoint("/wpush/a"), null, "Body");
        assertThat(adapter.send(noTitle, CALL).errorCode()).isEqualTo("PUSH_TITLE_MISSING");
        assertThat(service.received()).isEmpty();
    }

    @Test
    @DisplayName("it is configured only when the key pair and a contact exist, and says which is missing")
    void readinessNamesWhatIsMissing() {
        assertThat(adapter.describeAccount(AccountContext.EMPTY).complete()).isTrue();

        var none =
                adapter("", "", "", new PushEndpointPolicy("127.0.0.1", false)).describeAccount(AccountContext.EMPTY);
        assertThat(none.complete()).isFalse();
        assertThat(none.missingFields())
                .containsExactly(
                        "horecaos.push.vapid.public-key",
                        "horecaos.push.vapid.private-key-reference",
                        "horecaos.push.vapid.subject");

        assertThat(adapter("not-a-key", SECRET_REFERENCE, SUBJECT, new PushEndpointPolicy("x", false))
                        .describeAccount(AccountContext.EMPTY)
                        .missingFields())
                .as("a public key that is not a point on P-256 is not a key")
                .containsExactly("horecaos.push.vapid.public-key");
        assertThat(adapter(vapidPublicKey, SECRET_REFERENCE, "push@horecaos.uz", new PushEndpointPolicy("x", false))
                        .describeAccount(AccountContext.EMPTY)
                        .missingFields())
                .as("RFC 8292 wants a mailto: or https: contact")
                .containsExactly("horecaos.push.vapid.subject");
    }

    @Test
    @DisplayName("a title and a body that are too long are cut on a code point, never inside a pair")
    void longTextIsCutOnACodePoint() throws Exception {
        String title = "🍔".repeat(150);
        String body = "x".repeat(600);

        adapter.send(dispatch(service.endpoint("/wpush/long"), title, body), CALL);

        JsonNode payload = JSON.readTree(
                browser.read(service.receivedAt("/wpush/long").getFirst().body()));
        String shownTitle = payload.path("title").asString();
        assertThat(shownTitle.codePointCount(0, shownTitle.length())).isEqualTo(WebPushChannelAdapter.MAX_TITLE);
        assertThat(shownTitle).endsWith("…").doesNotContain("�");
        assertThat(payload.path("body").asString()).hasSize(WebPushChannelAdapter.MAX_BODY);
    }

    @Test
    @DisplayName("a delivery status can never be asked of a push service, so an unknown fate stays unknown")
    void queryStatusIsUncertain() {
        ProviderOutcome outcome = adapter.queryStatus("key", CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
    }

    // ------------------------------------------------------------------------------ fixtures

    private PushEnvelope envelope() {
        return new PushEnvelope(
                browser.p256dh(),
                browser.auth(),
                "https://api.horecaos.test/providers/push/clicks/token",
                "https://api.horecaos.test/api/v1/storefront/tenants/t/media/a?variant=w800",
                "/menu",
                "topic-0123456789abcdefgh",
                3600);
    }

    private NotificationDispatch dispatch(String endpoint, @Nullable String title, String body) {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "PUSH",
                endpoint,
                title,
                body,
                "key",
                "corr",
                "MarketingCampaign",
                UUID.randomUUID(),
                "PROMO",
                NotificationDispatch.PURPOSE_MARKETING,
                null,
                envelope());
    }
}
