package uz.horecaos.platform.partner.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.partnerclients.PartnerClientProvisioner;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Issues, lists, rotates and revokes a {@code partner.api_clients} row as a
 * real Keycloak {@code client_credentials} client (ADR 0040, ADR 0106,
 * gap-map row 10.8d).
 *
 * <p>Before this class, the only path to a live partner credential was a
 * database operator inserting a row by hand — exactly the {@code
 * base64(login:password)} handover ADR 0040's own migration comment says this
 * model replaces.
 *
 * <p><strong>Every method calls Keycloak before it opens a database
 * transaction, never inside one.</strong> The same ordering {@code
 * DeviceEnrolmentService.claim} and every rotate-by-value flow in this
 * codebase already use for an external call whose outcome is uncertain: mint
 * or change the Keycloak side first, then commit the local row only once that
 * has actually succeeded. A database write cannot roll back a Keycloak call
 * that already happened, so the call goes first and the commit follows it,
 * never the reverse.
 */
@Service
public class PartnerApiClientService {

    /**
     * How long an issued or rotated partner secret is valid before it must be
     * rotated again. Not an ADR 0028 policy import — no shared rotation-period
     * registry exists yet for a tenant-facing credential — so this is a local,
     * named constant a reviewer can adjust rather than a bare literal.
     */
    static final Duration SECRET_LIFETIME = Duration.ofDays(180);

    private final JdbcPartnerStore store;
    private final PartnerClientProvisioner provisioner;
    private final SecretIngressGateway door;
    private final AuditRecorder audit;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;

    public PartnerApiClientService(
            JdbcPartnerStore store,
            PartnerClientProvisioner provisioner,
            SecretIngressGateway door,
            AuditRecorder audit,
            TransactionTemplate unitOfWork,
            Clock clock) {
        this.store = store;
        this.provisioner = provisioner;
        this.door = door;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    public List<JdbcPartnerStore.PartnerClientView> list(UUID tenantId, UUID installationId) {
        return store.listClients(tenantId, installationId);
    }

    /**
     * @param displayLabel a human-legible name for the Keycloak client, never
     *                     read back by the application — support and platform
     *                     operators are the only readers, in the Keycloak
     *                     admin console itself
     */
    public IssuedSecret issue(UUID tenantId, UUID installationId, String displayLabel, ActorRef actor, String reason) {

        if (!store.isMarketplaceInstallation(tenantId, installationId)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "A partner API client may only be issued against a MARKETPLACE installation");
        }

        // Keycloak first, outside any transaction — see this class's own doc comment.
        PartnerClientProvisioner.ProvisionedClient client = provisioner.create(displayLabel);
        String secretValue = provisioner.regenerateSecret(client.keycloakClientRef());

        Instant now = clock.instant();
        Instant expiresAt = now.plus(SECRET_LIFETIME);
        var reference =
                door.write(SecretCategory.PROVIDER_MARKETPLACE, ownerScopeFor(tenantId), SecretValue.of(secretValue));

        UUID id = Ids.newId();
        try {
            unitOfWork.executeWithoutResult(status -> {
                store.insertClient(
                        id,
                        tenantId,
                        installationId,
                        client.clientId(),
                        client.keycloakClientRef(),
                        reference.toString(),
                        now,
                        expiresAt);
                record(tenantId, "partner.api_client_issued", id, actor, reason);
            });
        } catch (DuplicateKeyException alreadyLive) {
            // The installation already holds a PENDING or ACTIVE credential
            // (V0038's uq_partner_client_active_per_installation). The Keycloak
            // client this method just created is left disabled-by-absence: it
            // holds no row, so PartnerAuthenticationService.findClientByClientId
            // never resolves it and it authorizes nothing even if somebody later
            // found its secret — the same "orphaned but harmless" posture
            // DeviceEnrolmentService.approve's own doc comment accepts for the
            // identical race.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This installation already has a live partner API client");
        }

        return new IssuedSecret(id, client.clientId(), secretValue, expiresAt, 1);
    }

    public IssuedSecret rotate(UUID tenantId, UUID clientRowId, int expectedVersion, ActorRef actor, String reason) {

        JdbcPartnerStore.ClientKeycloakRef existing = store.findClientKeycloakRef(tenantId, clientRowId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such partner API client"));
        if (existing.keycloakClientRef() == null) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This client has no Keycloak client to rotate");
        }

        String secretValue = provisioner.regenerateSecret(existing.keycloakClientRef());
        Instant now = clock.instant();
        Instant expiresAt = now.plus(SECRET_LIFETIME);
        var reference =
                door.write(SecretCategory.PROVIDER_MARKETPLACE, ownerScopeFor(tenantId), SecretValue.of(secretValue));

        boolean rotated = unitOfWork.execute(status -> {
            boolean updated = store.rotateClientSecret(
                    tenantId, clientRowId, expectedVersion, reference.toString(), now, expiresAt);
            if (updated) {
                record(tenantId, "partner.api_client_secret_rotated", clientRowId, actor, reason);
            }
            return updated;
        });

        if (!rotated) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This partner API client was changed by someone else; reload and retry");
        }

        JdbcPartnerStore.PartnerClientView view = store.findClientView(tenantId, clientRowId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such partner API client"));
        return new IssuedSecret(view.id(), view.clientId(), secretValue, expiresAt, view.version());
    }

    public boolean revoke(UUID tenantId, UUID clientRowId, int expectedVersion, ActorRef actor, String reason) {
        JdbcPartnerStore.ClientKeycloakRef existing = store.findClientKeycloakRef(tenantId, clientRowId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such partner API client"));

        if (existing.keycloakClientRef() != null) {
            provisioner.disable(existing.keycloakClientRef());
        }

        boolean revoked = unitOfWork.execute(status -> {
            boolean updated = store.revokeClient(tenantId, clientRowId, expectedVersion);
            if (updated) {
                record(tenantId, "partner.api_client_revoked", clientRowId, actor, reason);
            }
            return updated;
        });
        return Boolean.TRUE.equals(revoked);
    }

    private static String ownerScopeFor(UUID tenantId) {
        return "tenant-" + tenantId;
    }

    /**
     * The one moment a partner API client's plaintext secret exists outside
     * Keycloak and the ADR 0028 secret store: the response to {@code issue}
     * or {@code rotate}, and never again. Neither this service nor {@code
     * JdbcPartnerStore} retains it — only the reference {@link
     * SecretIngressGateway#write} minted is persisted.
     */
    public record IssuedSecret(UUID id, String clientId, String secretValue, Instant secretExpiresAt, int version) {}

    private void record(UUID tenantId, String actionCode, UUID targetId, ActorRef actor, String reason) {
        audit.record(AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("PartnerApiClient", targetId)
                .because(reason)
                .correlatedBy(targetId.toString())
                .occurredAt(clock.instant())
                .build());
    }
}
