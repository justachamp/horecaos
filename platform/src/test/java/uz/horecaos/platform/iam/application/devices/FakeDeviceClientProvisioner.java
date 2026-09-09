package uz.horecaos.platform.iam.application.devices;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory stand-in for {@link DeviceClientProvisioner}, for tests that
 * exercise {@code DeviceEnrolmentService}'s real SQL against a real
 * PostgreSQL without a real Keycloak.
 *
 * <p>{@code KeycloakDeviceClientProvisioner} itself is proven separately,
 * against a real realm, the same way {@code KeycloakOrganizationIntegrationTests}
 * proves its own adapter — see that class's own doc for why a stub of the
 * Admin API would prove nothing about whether Keycloak behaves as assumed.
 * This fake exists only so the database-boundary properties ADR 0079 makes —
 * one-shot credential claim, `LOCATION`-only scope, immediate revocation —
 * can be tested without that dependency.
 *
 * <p>Public rather than package-private: {@code kitchen.application.KitchenDeviceServiceTests}
 * reuses it too, for the identical reason — proving what {@code kitchen} owns
 * without a real Keycloak.
 */
public final class FakeDeviceClientProvisioner implements DeviceClientProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicInteger counter = new AtomicInteger();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private volatile @Nullable String lastCreatedInternalId;

    @Override
    public ProvisionedClient create(String displayLabel) {
        int n = counter.incrementAndGet();
        lastCreatedInternalId = "internal-" + n;
        return new ProvisionedClient(lastCreatedInternalId, "kds-device-fake-" + n, "fake-device-subject-" + n);
    }

    /** The most recently provisioned client's internal id, for a test to assert against. */
    public @Nullable String lastCreatedInternalId() {
        return lastCreatedInternalId;
    }

    @Override
    public String regenerateSecret(String keycloakClientInternalId) {
        byte[] material = new byte[16];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    @Override
    public void disable(String keycloakClientInternalId) {
        disabled.add(keycloakClientInternalId);
    }

    @Override
    public String tokenEndpoint() {
        return "https://fake-keycloak.test/realms/horecaos/protocol/openid-connect/token";
    }

    public boolean isDisabled(@Nullable String keycloakClientInternalId) {
        return keycloakClientInternalId != null && disabled.contains(keycloakClientInternalId);
    }
}
