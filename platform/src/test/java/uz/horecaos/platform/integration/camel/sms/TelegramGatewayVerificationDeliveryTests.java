package uz.horecaos.platform.integration.camel.sms;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.customers.spi.VerificationCodeTransport;
import uz.horecaos.platform.customers.spi.VerificationCodeTransport.ContactChannel;
import uz.horecaos.platform.customers.spi.VerificationCodeTransport.Outcome;
import uz.horecaos.platform.customers.spi.VerificationCodeTransport.VerificationMessage;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.provider.SmsAccountLookup.SmsAccount;
import uz.horecaos.platform.integration.provider.telegramgateway.FakeTelegramGateway;
import uz.horecaos.platform.integration.provider.telegramgateway.TelegramGatewayClient;

/**
 * ADR 0063's delivery-policy seam: Gateway first when a token is configured, SMS
 * fallback on refusal, and which one actually carried the code recorded on the
 * outcome the caller — {@code CustomerVerificationService} — turns into a
 * challenge-row column.
 *
 * <p>Against two real sockets ({@link FakeTelegramGateway} and
 * {@link RecordingSmsGateway}) and a real Camel route for the SMS half, the same
 * "prove the bytes, not the stub" discipline {@link SmsVerificationRouteTests}
 * already applies to the SMS-only path.
 */
class TelegramGatewayVerificationDeliveryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final String CODE = "482913";
    private static final String TOKEN = "gateway-token-1";

    private @Nullable CamelContext camel;
    private @Nullable FakeTelegramGateway telegramGateway;

    @AfterEach
    void tearDown() {
        if (camel != null) {
            camel.stop();
        }
        if (telegramGateway != null) {
            telegramGateway.close();
        }
    }

    @Test
    @DisplayName("Gateway takes the message, SMS is never asked, and the channel and cost are reported")
    void gatewaySucceedsAndSmsIsNeverAsked() throws Exception {
        telegramGateway = FakeTelegramGateway.start();
        telegramGateway.expectToken(TOKEN);
        telegramGateway.setRequestCostUsd(0.03);

        try (RecordingSmsGateway sms = RecordingSmsGateway.start()) {
            Outcome outcome = transport(sms, configuredGateway()).send(message());

            assertThat(outcome.status()).isEqualTo(Outcome.Status.ACCEPTED);
            assertThat(outcome.deliveryChannel()).isEqualTo("TELEGRAM_GATEWAY");
            assertThat(outcome.providerMessageId()).isEqualTo("tg-req-1");
            // $0.03 -> 3 minor units (cents), never a bare float on the way in.
            assertThat(outcome.costMinor()).isEqualTo(3L);
            assertThat(outcome.costCurrencyCode()).isEqualTo("USD");

            assertThat(telegramGateway.requestsSent()).isEqualTo(1);
            assertThat(telegramGateway.phonesSentTo()).containsExactly("998901112233");
            assertThat(telegramGateway.authorizationHeadersReceived()).containsExactly("Bearer " + TOKEN);
            assertThat(sms.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("a Gateway refusal (no Telegram account on this number) falls back to SMS")
    void gatewayRefusalFallsBackToSms() throws Exception {
        telegramGateway = FakeTelegramGateway.start();
        telegramGateway.expectToken(TOKEN);
        telegramGateway.behaveAs(FakeTelegramGateway.Scenario.NO_TELEGRAM_ACCOUNT);

        try (RecordingSmsGateway sms = RecordingSmsGateway.start()) {
            sms.reply("/send", """
                    {"status":{"code":0,"description":"success"},"id":"5981980","parts":1}""");

            Outcome outcome = transport(sms, configuredGateway()).send(message());

            assertThat(outcome.status()).isEqualTo(Outcome.Status.ACCEPTED);
            assertThat(outcome.deliveryChannel()).isEqualTo("SMS");
            assertThat(telegramGateway.requestsSent()).isEqualTo(1);
            assertThat(sms.callsTo("/send")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a Gateway error (rate limited, or the provider unreachable) also falls back to SMS")
    void gatewayErrorFallsBackToSms() throws Exception {
        telegramGateway = FakeTelegramGateway.start();
        telegramGateway.expectToken(TOKEN);
        telegramGateway.behaveAs(FakeTelegramGateway.Scenario.SERVER_ERROR);

        try (RecordingSmsGateway sms = RecordingSmsGateway.start()) {
            sms.reply("/send", """
                    {"status":{"code":0,"description":"success"},"id":"5981980","parts":1}""");

            Outcome outcome = transport(sms, configuredGateway()).send(message());

            assertThat(outcome.status()).isEqualTo(Outcome.Status.ACCEPTED);
            assertThat(outcome.deliveryChannel()).isEqualTo("SMS");
            assertThat(sms.callsTo("/send")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("with no Gateway token configured, SMS is used directly and Gateway is never called")
    void unconfiguredGatewaySkipsStraightToSms() throws Exception {
        telegramGateway = FakeTelegramGateway.start();
        telegramGateway.expectToken(TOKEN);

        try (RecordingSmsGateway sms = RecordingSmsGateway.start()) {
            sms.reply("/send", """
                    {"status":{"code":0,"description":"success"},"id":"5981980","parts":1}""");

            Outcome outcome = transport(sms, unconfiguredGateway()).send(message());

            assertThat(outcome.status()).isEqualTo(Outcome.Status.ACCEPTED);
            assertThat(outcome.deliveryChannel()).isEqualTo("SMS");
            assertThat(telegramGateway.requestsSent()).isZero();
            assertThat(sms.callsTo("/send")).isEqualTo(1);
        }
    }

    private VerificationCodeTransport transport(RecordingSmsGateway sms, TelegramGatewayClient gateway)
            throws Exception {
        SmsGateway smsGateway = new SmsGateway(
                new StubLookup(sms.baseUrl()),
                binding -> Optional.of(new SmsAccount("horecaos", "16888")),
                fixedSmsResolver(),
                new VasSmsGatewayAdapter(
                        new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier())));

        SmsProcessor processor = new SmsProcessor(smsGateway, new SimpleMeterRegistry());

        camel = new DefaultCamelContext();
        camel.addRoutes(new SmsRouteBuilder(processor));
        camel.start();
        return new CamelVerificationCodeTransport(camel.createProducerTemplate(), gateway);
    }

    private TelegramGatewayClient configuredGateway() {
        return new TelegramGatewayClient(
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()),
                fixedGatewayResolver(),
                requireGateway().baseUrl(),
                secretReference().toString());
    }

    private TelegramGatewayClient unconfiguredGateway() {
        return new TelegramGatewayClient(
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()),
                fixedGatewayResolver(),
                requireGateway().baseUrl(),
                "");
    }

    private FakeTelegramGateway requireGateway() {
        return java.util.Objects.requireNonNull(telegramGateway, "Each test starts its own FakeTelegramGateway first");
    }

    private static SecretReference secretReference() {
        return new SecretReference(
                "local",
                uz.horecaos.platform.iam.api.secrets.SecretCategory.PROVIDER_NOTIFICATION,
                "platform",
                "telegram-gateway");
    }

    private static SecretResolver fixedGatewayResolver() {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of(TOKEN);
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of(TOKEN);
            }
        };
    }

    private static SecretResolver fixedSmsResolver() {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of("sms-key");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of("sms-key");
            }
        };
    }

    private static VerificationMessage message() {
        return new VerificationMessage(
                TENANT,
                BRAND,
                UUID.randomUUID(),
                ContactChannel.SMS,
                "998901112233",
                CODE,
                Duration.ofMinutes(5),
                "uz",
                Instant.parse("2026-08-25T09:15:00Z"));
    }

    private record StubLookup(String baseUrl) implements ProviderInstallationLookup {

        private static final SecretReference REFERENCE = new SecretReference(
                "local", uz.horecaos.platform.iam.api.secrets.SecretCategory.PROVIDER_NOTIFICATION, "tenant", "smsgw");

        @Override
        public Optional<BindingRef> primaryBinding(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            return Optional.of(new BindingRef(
                    UUID.randomUUID(),
                    INSTALLATION,
                    tenantId,
                    ProviderCategory.NOTIFICATION,
                    VasSmsGatewayAdapter.PROVIDER_TYPE,
                    brandId,
                    null));
        }

        @Override
        public List<BindingRef> candidateBindings(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            return List.of();
        }

        @Override
        public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
            return Optional.of(new InstallationSnapshot(
                    INSTALLATION,
                    ProviderCategory.NOTIFICATION,
                    VasSmsGatewayAdapter.PROVIDER_TYPE,
                    "local",
                    baseUrl,
                    "ACTIVE",
                    REFERENCE.toString(),
                    "v1"));
        }
    }
}
