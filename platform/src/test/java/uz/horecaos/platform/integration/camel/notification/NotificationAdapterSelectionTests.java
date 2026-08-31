package uz.horecaos.platform.integration.camel.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;

/**
 * Which adapter answers for a binding (ADR 0026).
 *
 * <p>This became worth asserting the moment a second SMS provider was
 * implemented. Selecting on channel alone was correct while there was one adapter
 * per channel and quietly wrong afterwards, and the failure is silent: every
 * message for the affected tenant is posted at the right base URL in the wrong
 * request shape, refused, and marked permanently rejected.
 */
class NotificationAdapterSelectionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();

    @Test
    @DisplayName("a binding for a provider no wired adapter speaks is refused, not guessed at")
    void aMismatchedProviderTypeIsRefused() {
        CountingAdapter adapter = new CountingAdapter();
        NotificationGateway gateway = new NotificationGateway(List.of(adapter), lookup("SMSGW_VAS"), resolver());

        ProviderOutcome outcome = gateway.send(dispatch());

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("PROVIDER_ADAPTER_MISMATCH");
        assertThat(adapter.sends).isZero();
    }

    @Test
    @DisplayName("a binding for the adapter's own provider is called")
    void amatchingProviderTypeIsCalled() {
        CountingAdapter adapter = new CountingAdapter();
        NotificationGateway gateway = new NotificationGateway(List.of(adapter), lookup("GENERIC_SMS"), resolver());

        assertThat(gateway.send(dispatch()).status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(adapter.sends).isEqualTo(1);
    }

    private static NotificationDispatch dispatch() {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TENANT,
                BRAND,
                null,
                "SMS",
                "998901112233",
                null,
                "your order is on its way",
                "key-1",
                "corr-1");
    }

    private static ProviderInstallationLookup lookup(String providerType) {
        SecretReference reference =
                new SecretReference("local", SecretCategory.PROVIDER_NOTIFICATION, "tenant", "gateway");
        return new ProviderInstallationLookup() {
            @Override
            public Optional<BindingRef> primaryBinding(
                    UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
                return Optional.of(new BindingRef(
                        UUID.randomUUID(),
                        INSTALLATION,
                        tenantId,
                        ProviderCategory.NOTIFICATION,
                        providerType,
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
                        providerType,
                        "local",
                        "http://127.0.0.1:1",
                        "ACTIVE",
                        reference.toString(),
                        "v1"));
            }
        };
    }

    private static SecretResolver resolver() {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of("token");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of("token");
            }
        };
    }

    /** Counts calls, so "was the wrong adapter reached" is answerable directly. */
    private static final class CountingAdapter implements NotificationChannelAdapter {

        private int sends;

        @Override
        public String providerType() {
            return "GENERIC_SMS";
        }

        @Override
        public String channel() {
            return "SMS";
        }

        @Override
        public ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call) {
            sends++;
            return ProviderOutcome.success(java.util.Map.of("providerStatus", "ACCEPTED"), "ext-1");
        }

        @Override
        public ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call) {
            return ProviderOutcome.success(java.util.Map.of("providerStatus", "SENT"), "ext-1");
        }
    }
}
