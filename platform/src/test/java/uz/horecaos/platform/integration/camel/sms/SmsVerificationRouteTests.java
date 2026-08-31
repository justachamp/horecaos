package uz.horecaos.platform.integration.camel.sms;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
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
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.provider.SmsAccountLookup.SmsAccount;

/**
 * The route's policy, end to end, from the port a customer's code leaves through
 * to the bytes on the socket (ADR 0007, ADR 0015).
 *
 * <p>Against a real Camel context and a real socket, because the behaviour under
 * test is what the route does <em>next</em> after each outcome — and specifically
 * that it never does the one thing that would text somebody twice.
 */
class SmsVerificationRouteTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final String CODE = "482913";

    private @Nullable CamelContext camel;

    @AfterEach
    void stopCamel() {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    @DisplayName("an accepted send reaches the customer and reports accepted")
    void anAcceptedSendIsAccepted() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":0,"description":"success"},"id":"5981980","parts":1}""");

            Outcome outcome = transport(fake).send(message());

            assertThat(outcome.status()).isEqualTo(Outcome.Status.ACCEPTED);
            assertThat(fake.callsTo("/send")).isEqualTo(1);
            assertThat(fake.callsTo("/search")).isZero();
            // The text the customer reads carries the code and the minutes the
            // challenge row will actually honour.
            assertThat(String.valueOf(fake.callTo("/send").body().get("text")))
                    .contains(CODE)
                    .contains("5 daqiqa");
        }
    }

    @Test
    @DisplayName("a lost answer searches instead of sending again")
    void aLostAnswerResolvesBySearching() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.stallAfterReceiving("/send", 400);
            fake.reply("/search", """
                    {"status":{"code":0,"description":"success"},
                     "data":[{"id":723923,"msg":"HorecaOS: kod 482913. 5 daqiqa amal qiladi.",
                              "send_dt":1,"status":3}]}""");

            Outcome outcome = transport(fake, Duration.ofMillis(200)).send(message());

            // The whole reason this route exists. A second /send here is a second
            // SMS to a real person, on a provider with no idempotency key.
            assertThat(fake.callsTo("/send")).isEqualTo(1);
            assertThat(fake.callsTo("/search")).isEqualTo(1);
            assertThat(outcome.status()).isEqualTo(Outcome.Status.ACCEPTED);
        }
    }

    @Test
    @DisplayName("a search that finds nothing refuses the issuance rather than resending")
    void anUnconfirmedSendIsUnavailableAndNeverRepeated() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.stallAfterReceiving("/send", 400);
            fake.reply("/search", """
                    {"status":{"code":0,"description":"success"},"data":[]}""");

            Outcome outcome = transport(fake, Duration.ofMillis(200)).send(message());

            assertThat(fake.callsTo("/send")).isEqualTo(1);
            // The customer is told the code could not be sent and asks again, which
            // opens a fresh challenge with a fresh code — never a copy of this one.
            assertThat(outcome.status()).isEqualTo(Outcome.Status.UNAVAILABLE);
            assertThat(outcome.reasonCode()).isEqualTo("SMS_SEND_UNCONFIRMED");
        }
    }

    @Test
    @DisplayName("a blacklisted receiver comes back as its own reason, and no search runs")
    void blacklistIsFinalAndNeedsNoReconciliation() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":20,"description":"receiver in blacklist"}}""");

            Outcome outcome = transport(fake).send(message());

            assertThat(outcome.status()).isEqualTo(Outcome.Status.REFUSED);
            // Reaches the storefront as an ADR 0031 problem property, so a person
            // can be told they will never receive a code on this number.
            assertThat(outcome.reasonCode()).isEqualTo("SMS_RECEIVER_BLACKLISTED");
            assertThat(fake.callsTo("/search")).isZero();
        }
    }

    @Test
    @DisplayName("no outcome the caller sees carries the destination or the code")
    void nothingLeakingCrossesThePort() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", 500, "{\"description\":\"998901112233 / 482913\"}");

            Outcome outcome = transport(fake).send(message());

            assertThat(String.valueOf(outcome.reasonCode()))
                    .doesNotContain("998901112233")
                    .doesNotContain(CODE);
        }
    }

    @Test
    @DisplayName("with no route running the transport answers rather than throwing")
    void anUnreachableRouteIsAnOutcomeNotAnException() throws Exception {
        camel = new DefaultCamelContext();
        camel.start();
        try (ProducerTemplate producer = camel.createProducerTemplate()) {
            VerificationCodeTransport transport = new CamelVerificationCodeTransport(producer);

            Outcome outcome = transport.send(message());

            // The port's contract: a provider failure is an outcome, because
            // whether the challenge is kept or torn down is the caller's decision.
            assertThat(outcome.status()).isEqualTo(Outcome.Status.UNAVAILABLE);
            assertThat(outcome.reasonCode()).isEqualTo("SMS_ROUTE_UNAVAILABLE");
        }
    }

    private VerificationCodeTransport transport(RecordingSmsGateway fake) throws Exception {
        return transport(fake, Duration.ofSeconds(5));
    }

    private VerificationCodeTransport transport(RecordingSmsGateway fake, Duration timeout) throws Exception {

        SmsGateway gateway = new SmsGateway(
                new StubLookup(fake.baseUrl()),
                binding -> Optional.of(new SmsAccount("horecaos", "16888")),
                fixedResolver(),
                new VasSmsGatewayAdapter(
                        new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier())),
                timeout);

        SmsProcessor processor = new SmsProcessor(gateway, new SimpleMeterRegistry());

        camel = new DefaultCamelContext();
        camel.addRoutes(new SmsRouteBuilder(processor));
        camel.start();
        return new CamelVerificationCodeTransport(camel.createProducerTemplate());
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

    private static SecretResolver fixedResolver() {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of("the-key");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of("the-key");
            }
        };
    }

    private record StubLookup(String baseUrl) implements ProviderInstallationLookup {

        private static final SecretReference REFERENCE =
                new SecretReference("local", SecretCategory.PROVIDER_NOTIFICATION, "tenant", "smsgw");

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
