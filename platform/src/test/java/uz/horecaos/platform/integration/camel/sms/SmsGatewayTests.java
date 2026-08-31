package uz.horecaos.platform.integration.camel.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.provider.SmsAccountLookup.SmsAccount;

/**
 * Binding resolution and credential handling on the way to the gateway
 * (ADR 0026, ADR 0028).
 */
class SmsGatewayTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final SecretReference REFERENCE =
            new SecretReference("local", SecretCategory.PROVIDER_NOTIFICATION, "tenant", "smsgw");

    @Test
    @DisplayName("a binding for another SMS provider is refused rather than spoken to")
    void anotherProvidersBindingIsRefused() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            SmsGateway gateway = gateway(fake, "GENERIC_SMS", account(), "ACTIVE");

            ProviderOutcome outcome = gateway.send(operation());

            // SEND_SMS is shared with ADR 0020's notification path, so a tenant may
            // well have a different gateway bound for it. Speaking this protocol at
            // that endpoint would post a credential and a live code to a service
            // nobody has agreed the shape of.
            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo("SMS_PROVIDER_UNSUPPORTED");
            assertThat(fake.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("an account with no login or sender is refused before anything is sent")
    void missingAccountConfigurationIsRefusedBeforeTheCall() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            SmsGateway gateway =
                    gateway(fake, VasSmsGatewayAdapter.PROVIDER_TYPE, new SmsAccount("horecaos", null), "ACTIVE");

            ProviderOutcome outcome = gateway.send(operation());

            // A missing sender comes back as 15 and a missing login as 10, both
            // after the credential has been put on the wire for nothing.
            assertThat(outcome.errorCode()).isEqualTo("SMS_ACCOUNT_MISCONFIGURED");
            assertThat(fake.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("a suspended installation is not called")
    void suspendedInstallationIsNotCalled() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            SmsGateway gateway = gateway(fake, VasSmsGatewayAdapter.PROVIDER_TYPE, account(), "SUSPENDED");

            assertThat(gateway.send(operation()).errorCode()).isEqualTo("INSTALLATION_INACTIVE");
            assertThat(fake.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("wrong key reads the secret once past the cache, and only once")
    void wrongKeyRefreshesTheSecretExactlyOnce() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":13,"description":"wrong key"}}""");
            RecordingResolver secrets = new RecordingResolver();
            SmsGateway gateway = gateway(fake, VasSmsGatewayAdapter.PROVIDER_TYPE, account(), "ACTIVE", secrets);

            ProviderOutcome outcome = gateway.send(operation());

            // Once, not in a loop: a genuinely stale OpenBao copy has to surface as
            // an incident rather than as retry traffic. Repeating the send is safe
            // for this code and this code only — 13 is the provider answering
            // instead of sending.
            assertThat(secrets.fresh).isEqualTo(1);
            assertThat(fake.callsTo("/send")).isEqualTo(2);
            assertThat(outcome.errorCode()).isEqualTo("PROVIDER_AUTHENTICATION");
        }
    }

    @Test
    @DisplayName("a blacklist refusal is not mistaken for a credential problem")
    void aBusinessRefusalDoesNotRefreshTheCredential() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":20,"description":"receiver in blacklist"}}""");
            RecordingResolver secrets = new RecordingResolver();
            SmsGateway gateway = gateway(fake, VasSmsGatewayAdapter.PROVIDER_TYPE, account(), "ACTIVE", secrets);

            assertThat(gateway.send(operation()).errorCode()).isEqualTo("SMS_RECEIVER_BLACKLISTED");
            assertThat(secrets.fresh).isZero();
            assertThat(fake.callsTo("/send")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("no binding at all is a refusal, not a retry")
    void noBindingIsARefusal() {
        SmsGateway gateway = new SmsGateway(
                new StubLookup(null, "ACTIVE"), binding -> Optional.empty(), new RecordingResolver(), adapter());

        ProviderOutcome outcome = gateway.send(operation());

        // Retrying on a timer would hide a configuration gap behind a growing
        // backlog of customers who cannot sign in.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("NO_PROVIDER_BINDING");
    }

    private static SmsAccount account() {
        return new SmsAccount("horecaos", "16888");
    }

    private static SmsVerificationOperation operation() {
        return new SmsVerificationOperation(
                SmsVerificationOperation.Kind.SEND,
                TENANT,
                BRAND,
                UUID.randomUUID(),
                "998901112233",
                "482913",
                "HorecaOS code 482913",
                Instant.parse("2026-08-25T09:15:00Z"));
    }

    private static VasSmsGatewayAdapter adapter() {
        return new VasSmsGatewayAdapter(
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()));
    }

    private static SmsGateway gateway(
            RecordingSmsGateway fake, String providerType, SmsAccount account, String status) {
        return gateway(fake, providerType, account, status, new RecordingResolver());
    }

    private static SmsGateway gateway(
            RecordingSmsGateway fake, String providerType, SmsAccount account, String status, SecretResolver secrets) {
        return new SmsGateway(
                new StubLookup(providerType, status, fake.baseUrl()),
                binding -> Optional.ofNullable(account),
                secrets,
                adapter());
    }

    /** Answers one binding and one installation, with the shapes each test needs. */
    private record StubLookup(@Nullable String providerType, String status, String baseUrl)
            implements ProviderInstallationLookup {

        StubLookup(@Nullable String providerType, String status) {
            this(providerType, status, "http://127.0.0.1:1");
        }

        @Override
        public Optional<BindingRef> primaryBinding(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            if (providerType == null) {
                return Optional.empty();
            }
            return Optional.of(new BindingRef(
                    BINDING, INSTALLATION, tenantId, ProviderCategory.NOTIFICATION, providerType, brandId, null));
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
                    // Every test that reaches installation() first resolved a
                    // binding through primaryBinding(), which already refused a
                    // null providerType above.
                    Objects.requireNonNull(providerType),
                    "local",
                    baseUrl,
                    status,
                    REFERENCE.toString(),
                    "v1"));
        }
    }

    /** Counts reads past the cache, which is the ADR 0028 behaviour under test. */
    private static final class RecordingResolver implements SecretResolver {

        private int fresh;

        @Override
        public SecretValue resolve(SecretReference reference) {
            return SecretValue.of("the-key");
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            fresh++;
            return SecretValue.of("the-key");
        }
    }
}
