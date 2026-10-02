package uz.horecaos.platform.integration.camel.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.marketplace.PushConclusion;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * The refusal paths of the marketplace gateway and the conclusion drawn from every outcome (ADR
 * 0141 Decision 7): the table that says what the platform may believe the partner holds after
 * each kind of failure.
 *
 * <p>The refusals are the ones that provably wrote nothing — a missing installation, a suspended
 * one, an adapter that cannot build its authorization — and they must conclude {@code
 * NOT_APPLIED}, not {@code UNKNOWN}: concluding unknown for a call that never left would withdraw
 * a belief that was still true and resend an entire binding for nothing.
 */
class MarketplaceGatewayTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();

    @Test
    @DisplayName("a missing installation is refused before anything is sent, and concludes NOT_APPLIED")
    void aMissingInstallationWritesNothing() {
        ProviderOutcome outcome = gateway(Optional.empty()).invoke(call(MarketplaceApiCall.fixedHeaders(Map.of())));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("INSTALLATION_MISSING");
        assertThat(PushConclusion.of(outcome)).isEqualTo(PushConclusion.NOT_APPLIED);
    }

    @Test
    @DisplayName(
            "a suspended installation is a deliberate stop: refused, NOT_APPLIED, and the credential is never resolved")
    void aSuspendedInstallationIsNotCalled() {
        RecordingSecrets secrets = new RecordingSecrets();
        MarketplaceGateway gateway =
                new MarketplaceGateway(lookup(Optional.of(installation("SUSPENDED"))), secrets, httpClient());

        ProviderOutcome outcome = gateway.invoke(call(MarketplaceApiCall.fixedHeaders(Map.of())));

        assertThat(outcome.errorCode()).isEqualTo("INSTALLATION_INACTIVE");
        assertThat(PushConclusion.of(outcome)).isEqualTo(PushConclusion.NOT_APPLIED);
        assertThat(secrets.resolutions)
                .as("a suspended installation may mean a compromised credential")
                .isZero();
    }

    @Test
    @DisplayName("an adapter whose authorization cannot be built is refused without echoing the exception message")
    void anUnbuildableAuthorizationDropsTheMessage() {
        MarketplaceGateway gateway = new MarketplaceGateway(
                lookup(Optional.of(installation("ACTIVE"))), new RecordingSecrets(), httpClient());

        ProviderOutcome outcome = gateway.invoke(call(credential -> {
            throw new IllegalStateException("token " + credential + " could not be signed");
        }));

        assertThat(outcome.errorCode()).isEqualTo("AUTHORIZATION_UNBUILDABLE");
        assertThat(outcome.detail())
                .as("the function that threw was holding the credential when it did")
                .isEqualTo("IllegalStateException")
                .doesNotContain("super-secret");
        assertThat(PushConclusion.of(outcome)).isEqualTo(PushConclusion.NOT_APPLIED);
    }

    @Test
    @DisplayName("what the platform may believe after each outcome")
    void theConclusionTable() {
        // A success answer.
        assertThat(PushConclusion.of(ProviderOutcome.success(Map.of(), null))).isEqualTo(PushConclusion.CONFIRMED);

        // Nothing was written, or the partner refused and changed nothing.
        for (String code :
                List.of("CIRCUIT_OPEN", "CONNECTION_FAILED", "CONNECT_TIMEOUT", "TRANSPORT_FAILURE", "RATE_LIMITED")) {
            assertThat(PushConclusion.of(ProviderOutcome.retryable(code, "x", Duration.ofSeconds(1))))
                    .as(code)
                    .isEqualTo(PushConclusion.NOT_APPLIED);
        }
        assertThat(PushConclusion.of(ProviderOutcome.rejected("PROVIDER_REJECTED", "422")))
                .as("a 4xx business answer changed nothing")
                .isEqualTo(PushConclusion.NOT_APPLIED);
        assertThat(PushConclusion.of(ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", "401")))
                .isEqualTo(PushConclusion.NOT_APPLIED);

        // The partner may or may not have acted.
        for (String code : List.of("READ_TIMEOUT", "CONNECTION_RESET", "PROVIDER_TIMEOUT", "RESPONSE_UNREADABLE")) {
            assertThat(PushConclusion.of(ProviderOutcome.uncertain(code, "x")))
                    .as(code)
                    .isEqualTo(PushConclusion.UNKNOWN);
        }
        assertThat(PushConclusion.of(ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "502", null)))
                .as("a 5xx whose contract does not promise atomicity")
                .isEqualTo(PushConclusion.UNKNOWN);
        assertThat(PushConclusion.of(ProviderOutcome.retryable("UNCLASSIFIED", "x", null)))
                .as("anything unclassified is unknown, not untouched")
                .isEqualTo(PushConclusion.UNKNOWN);
        assertThat(PushConclusion.of(
                        new ProviderOutcome(ProviderOutcome.Status.RETRYABLE, Map.of(), null, null, null, null)))
                .as("a retryable with no code at all")
                .isEqualTo(PushConclusion.UNKNOWN);

        // The partner does not know the item.
        assertThat(PushConclusion.of(ProviderOutcome.rejected(PushConclusion.UNKNOWN_ITEM, "no such item")))
                .isEqualTo(PushConclusion.REJECTED_UNMAPPED);
    }

    @Test
    @DisplayName("the call record does not render its body or its credential")
    void nothingSensitiveReachesALogLine() {
        String rendered = call(MarketplaceApiCall.fixedHeaders(Map.of("x-token", "super-secret")))
                .toString();

        assertThat(rendered).doesNotContain("super-secret").contains("availability.set");
    }

    // ------------------------------------------------------------------ fixtures

    private static MarketplaceGateway gateway(Optional<ProviderInstallationLookup.InstallationSnapshot> installation) {
        return new MarketplaceGateway(lookup(installation), new RecordingSecrets(), httpClient());
    }

    private static ProviderHttpClient httpClient() {
        return new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier());
    }

    private static ProviderInstallationLookup.InstallationSnapshot installation(String status) {
        return new ProviderInstallationLookup.InstallationSnapshot(
                INSTALLATION,
                ProviderCategory.MARKETPLACE,
                "FAKE_EDA",
                "fake-eda-sandbox",
                "https://sandbox.example.test",
                status,
                "horecaos:test:provider_marketplace:tenant:abc",
                "marketplace/fake-eda/v1");
    }

    private static MarketplaceApiCall call(java.util.function.Function<String, Map<String, String>> authorization) {
        return new MarketplaceApiCall(
                TENANT,
                UUID.randomUUID(),
                INSTALLATION,
                "FAKE_EDA",
                "availability.set",
                "PUT",
                "/items/ext-A",
                MarketplaceApiCall.fixedBody(Map.of("available", false)),
                authorization,
                "correlation-1",
                null);
    }

    private static ProviderInstallationLookup lookup(
            Optional<ProviderInstallationLookup.InstallationSnapshot> snapshot) {
        return new ProviderInstallationLookup() {
            @Override
            public Optional<BindingRef> primaryBinding(
                    UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
                return Optional.empty();
            }

            @Override
            public List<BindingRef> candidateBindings(
                    UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
                return List.of();
            }

            @Override
            public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
                return snapshot;
            }
        };
    }

    private static final class RecordingSecrets implements SecretResolver {
        int resolutions;

        @Override
        public SecretValue resolve(SecretReference reference) {
            resolutions++;
            return SecretValue.of("super-secret");
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            resolutions++;
            return SecretValue.of("super-secret");
        }
    }
}
