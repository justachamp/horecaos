package uz.horecaos.platform.integration.camel.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a route gets a credential, and what happens when the provider rejects it
 * (ADR 0026, ADR 0028).
 */
class DeliveryGatewayTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final SecretReference REFERENCE =
            new SecretReference("local", SecretCategory.PROVIDER_DELIVERY, "tenant", "noor-1");

    @Test
    @DisplayName("the adapter is handed a resolved credential, never a stored reference")
    void resolvesTheCredentialAtCallTime() {
        RecordingPartner partner = new RecordingPartner("noor-delivery", ProviderOutcome.success(java.util.Map.of(), "x"));
        CountingResolver resolver = new CountingResolver("live-token");
        DeliveryGateway gateway = new DeliveryGateway(List.of(partner), lookup("ACTIVE"), resolver);

        gateway.queryShipment(binding("noor-delivery"), "ext-1", "cmd-1");

        assertThat(partner.calls).hasSize(1);
        assertThat(partner.calls.getFirst().credential()).isEqualTo("live-token");
        // The reference itself must never reach the adapter: an adapter holding
        // one could resolve secrets it was not given.
        assertThat(partner.calls.getFirst().credential()).doesNotContain("horecaos:");
    }

    @Test
    @DisplayName("a rejected credential is refreshed past the cache exactly once")
    void refreshesOnceOnAuthenticationFailure() {
        RecordingPartner partner = new RecordingPartner("noor-delivery",
                ProviderOutcome.rejected("PROVIDER_UNAUTHORIZED", "401"));
        CountingResolver resolver = new CountingResolver("stale-token");
        DeliveryGateway gateway = new DeliveryGateway(List.of(partner), lookup("ACTIVE"), resolver);

        gateway.queryShipment(binding("noor-delivery"), "ext-1", "cmd-1");

        // A token rotated after we cached it is indistinguishable from a revoked
        // one until a fresh read. Once, though: a genuinely revoked credential
        // must become an incident, not a retry loop against the provider.
        assertThat(resolver.freshReads.get()).isEqualTo(1);
        assertThat(partner.calls).hasSize(2);
    }

    @Test
    @DisplayName("a healthy call never triggers a cache-bypassing read")
    void doesNotRefreshOnSuccess() {
        RecordingPartner partner = new RecordingPartner("noor-delivery",
                ProviderOutcome.success(java.util.Map.of(), "ext-1"));
        CountingResolver resolver = new CountingResolver("live-token");
        DeliveryGateway gateway = new DeliveryGateway(List.of(partner), lookup("ACTIVE"), resolver);

        gateway.queryShipment(binding("noor-delivery"), "ext-1", "cmd-1");

        assertThat(resolver.freshReads.get()).isZero();
    }

    @Test
    @DisplayName("a suspended installation is refused without calling the provider")
    void refusesASuspendedInstallation() {
        RecordingPartner partner = new RecordingPartner("noor-delivery",
                ProviderOutcome.success(java.util.Map.of(), "ext-1"));
        DeliveryGateway gateway = new DeliveryGateway(
                List.of(partner), lookup("SUSPENDED"), new CountingResolver("token"));

        ProviderOutcome outcome = gateway.queryShipment(binding("noor-delivery"), "ext-1", "cmd-1");

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("INSTALLATION_INACTIVE");
        // Suspension is usually deliberate — a rotation in progress, or a billing
        // stop. Calling anyway would earn a 401 and look like an outage.
        assertThat(partner.calls).isEmpty();
    }

    @Test
    @DisplayName("an unsupported capability is refused before any credential is resolved")
    void refusesAnUnsupportedCapability() {
        RecordingPartner partner = new RecordingPartner("noor-delivery",
                ProviderOutcome.success(java.util.Map.of(), "ext-1"));
        CountingResolver resolver = new CountingResolver("token");
        DeliveryGateway gateway = new DeliveryGateway(List.of(partner), lookup("ACTIVE"), resolver);

        ProviderOutcome outcome = gateway.confirmShipment(binding("noor-delivery"), "ext-1", "cmd-1");

        assertThat(outcome.errorCode()).isEqualTo("CAPABILITY_UNSUPPORTED");
        assertThat(resolver.reads.get()).isZero();
    }

    @Test
    @DisplayName("a binding with no registered adapter is refused, not silently dropped")
    void refusesAnUnknownProviderType() {
        DeliveryGateway gateway = new DeliveryGateway(List.of(), lookup("ACTIVE"), new CountingResolver("t"));

        ProviderOutcome outcome = gateway.queryShipment(binding("some-future-partner"), "ext-1", "cmd-1");

        assertThat(outcome.errorCode()).isEqualTo("NO_ADAPTER");
    }

    private static BindingRef binding(String providerType) {
        return new BindingRef(UUID.randomUUID(), INSTALLATION, TENANT,
                ProviderCategory.DELIVERY, providerType, BRAND, null);
    }

    private static ProviderInstallationLookup lookup(String status) {
        return new ProviderInstallationLookup() {
            @Override
            public Optional<BindingRef> primaryBinding(UUID tenantId, UUID brandId, UUID locationId, String code) {
                return Optional.empty();
            }

            @Override
            public List<BindingRef> candidateBindings(UUID tenantId, UUID brandId, UUID locationId, String code) {
                return List.of();
            }

            @Override
            public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
                return Optional.of(new InstallationSnapshot(
                        INSTALLATION, ProviderCategory.DELIVERY, "noor-delivery", "local",
                        "http://127.0.0.1:1", status, REFERENCE.toString(), "v1"));
            }
        };
    }

    /** Counts cached versus cache-bypassing reads, which is the behaviour under test. */
    private static final class CountingResolver implements SecretResolver {

        private final SecretValue value;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger freshReads = new AtomicInteger();

        private CountingResolver(String value) {
            this.value = SecretValue.of(value);
        }

        @Override
        public SecretValue resolve(SecretReference reference) {
            reads.incrementAndGet();
            return value;
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            freshReads.incrementAndGet();
            return value;
        }
    }

    /** A partner that records what it was handed and returns a scripted outcome. */
    private static final class RecordingPartner implements DeliveryPartner {

        private final String providerType;
        private final ProviderOutcome scripted;
        private final List<ProviderCall> calls = new ArrayList<>();

        private RecordingPartner(String providerType, ProviderOutcome scripted) {
            this.providerType = providerType;
            this.scripted = scripted;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Set<DeliveryCapability> capabilities() {
            return Set.of(DeliveryCapability.QUERY_SHIPMENT, DeliveryCapability.QUOTE_DELIVERY);
        }

        @Override
        public ProviderOutcome quote(DeliveryRequest request, ProviderCall call) {
            return record(call);
        }

        @Override
        public ProviderOutcome createShipment(DeliveryRequest request, ProviderCall call) {
            return record(call);
        }

        @Override
        public ProviderOutcome confirmShipment(String externalReference, ProviderCall call) {
            return record(call);
        }

        @Override
        public ProviderOutcome cancellationCost(String externalReference, ProviderCall call) {
            return record(call);
        }

        @Override
        public ProviderOutcome cancelShipment(String externalReference, String reason, ProviderCall call) {
            return record(call);
        }

        @Override
        public ProviderOutcome queryShipment(String externalReference, ProviderCall call) {
            return record(call);
        }

        private ProviderOutcome record(ProviderCall call) {
            calls.add(call);
            return scripted;
        }
    }
}
