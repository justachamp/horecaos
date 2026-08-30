package uz.horecaos.platform.partner.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.domain.PartnerClientStatus;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Turns an OAuth 2.0 client-credentials token into a partner principal
 * (ADR 0040, ADR 0028).
 *
 * <p>The credential is a Keycloak confidential client per ADR 0026 installation.
 * The token is minted by Keycloak and validated by the resource server like any
 * other; what this service adds is the step Keycloak cannot do, which is
 * resolving the client to a tenant and to the set of bindings it may act for.
 *
 * <p><strong>What this is not.</strong> It is not a static API key in a header,
 * and it is emphatically not {@code base64(login:password)} of a real panel
 * user — which is what the legacy estate's generic aggregator integration mints,
 * and which makes a partner credential indistinguishable from a human's
 * password. That shape cannot be rotated without an outage, cannot be scoped,
 * cannot expire, and puts a person's password in an aggregator's configuration
 * file. A confidential client can be rotated against the same client id, carries
 * its own expiry, and is revocable without touching anybody's account.
 *
 * <p>Four things are checked here and each of them is a way a live integration
 * fails. The credential must exist; it must be {@code ACTIVE}, so suspension is
 * an immediate switch rather than a configuration change somebody remembers to
 * make; its secret must not have expired, because an unexpiring partner secret
 * is a five-year-old secret; and the tenant in the path must be the credential's
 * own, because ADR 0031 requires it of every surface and a partner is the
 * principal least able to be trusted with a path parameter.
 *
 * <p>Every failure answers {@code UNAUTHENTICATED} with nothing in the detail.
 * Distinguishing "no such client" from "suspended" from "wrong tenant" tells a
 * caller which of its guesses was closest, and a partner surface is where that
 * matters most.
 */
@Service
public class PartnerAuthenticationService {

    private final JdbcPartnerStore store;
    private final Clock clock;

    public PartnerAuthenticationService(JdbcPartnerStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * @param clientId the {@code azp} / {@code client_id} claim of a
     *                 client-credentials token. A token issued for a human
     *                 carries a subject and an organization claim and never
     *                 reaches this method: the partner surface accepts only
     *                 clients registered here, so a staff token cannot be
     *                 replayed against it to read a partner's own view.
     */
    public PartnerPrincipal authenticate(String clientId, UUID tenantInPath) {
        Instant now = clock.instant();

        JdbcPartnerStore.PartnerClient client = store.findClientByClientId(clientId)
                .orElseThrow(PartnerAuthenticationService::denied);

        if (client.status() != PartnerClientStatus.ACTIVE) {
            throw denied();
        }
        if (client.secretExpiresAt() != null && !client.secretExpiresAt().isAfter(now)) {
            throw denied();
        }
        if (!client.tenantId().equals(tenantInPath)) {
            throw denied();
        }

        List<UUID> bindings = store.bindingsOf(client.tenantId(), client.installationId(), now);
        if (bindings.isEmpty()) {
            // A credential with no live binding can reach nothing, so refusing it
            // here rather than letting every subsequent read return an empty list
            // is the difference between "your venue is not configured" and a
            // partner concluding the restaurant has no orders.
            throw denied();
        }

        store.recordAuthentication(client.id(), now);
        return new PartnerPrincipal(client.id(), client.clientId(), client.tenantId(),
                client.installationId(), Set.copyOf(bindings));
    }

    private static ApiException denied() {
        return new ApiException(ErrorCode.UNAUTHENTICATED,
                "A valid partner client credential is required");
    }
}
