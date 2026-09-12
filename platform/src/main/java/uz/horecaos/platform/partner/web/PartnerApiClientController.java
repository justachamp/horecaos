package uz.horecaos.platform.partner.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.partner.application.PartnerApiClientService;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's own partner API credential as a real OAuth 2.0 client
 * (ADR 0040, ADR 0106, gap-map row 10.8d).
 *
 * <p>Before this controller, {@code partner.api_clients} could only ever grow
 * by a database operator inserting a row by hand — exactly the {@code
 * base64(login:password)} handover ADR 0040's own migration comment names as
 * what this model replaces. Issue, list, rotate and revoke all share one
 * capability, {@link Capability#PARTNER_API_CLIENT_MANAGE}, deliberately
 * separate from {@link Capability#INTEGRATION_INSTALLATION_MANAGE} — see that
 * constant's own doc comment for the blast-radius argument.
 *
 * <p>The secret value itself is never returned by {@code list}, matching
 * every other provider credential surface in this codebase: {@code issue} and
 * {@code rotate} are the only two responses that ever carry one, and only
 * once, at the moment of hand-off.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/integrations/{installationId}/partner-clients")
@Tag(
        name = "Partner API clients",
        description = "ADR 0040 inbound OAuth 2.0 credentials for a MARKETPLACE installation")
public class PartnerApiClientController {

    private final PartnerApiClientService clients;
    private final CurrentActor currentActor;

    public PartnerApiClientController(PartnerApiClientService clients, CurrentActor currentActor) {
        this.clients = clients;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(Capability.PARTNER_API_CLIENT_MANAGE)
    @Operation(
            summary = "List this installation's partner API clients",
            description = "Never the secret value — only the ADR 0028 reference, so an operator can see "
                    + "that a credential is configured and when it was last rotated or used.")
    List<PartnerClientResponse> list(@PathVariable UUID tenantId, @PathVariable UUID installationId) {
        return clients.list(tenantId, installationId).stream()
                .map(PartnerClientResponse::of)
                .toList();
    }

    @PostMapping
    @RequiresCapability(value = Capability.PARTNER_API_CLIENT_MANAGE, mutating = true)
    @Operation(
            summary = "Issue a new partner API client",
            description = "Creates a Keycloak client_credentials confidential client and returns its "
                    + "secret exactly once — the platform never stores or returns it again. Refused when "
                    + "the installation is not MARKETPLACE, or already has a live (PENDING or ACTIVE) "
                    + "client.")
    ResponseEntity<IssuedClientResponse> issue(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @Valid @RequestBody IssuePartnerClientRequest request) {

        PartnerApiClientService.IssuedSecret issued = clients.issue(
                tenantId,
                installationId,
                request.displayLabel(),
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return ResponseEntity.ok(new IssuedClientResponse(
                issued.id(), issued.clientId(), issued.secretValue(), issued.secretExpiresAt(), issued.version()));
    }

    @PostMapping("/{clientId}/secret-rotations")
    @RequiresCapability(value = Capability.PARTNER_API_CLIENT_MANAGE, mutating = true)
    @Operation(
            summary = "Rotate a partner API client's secret",
            description = "Mints a fresh Keycloak secret and returns it exactly once. The client id and "
                    + "the installation stay put; only what the reference resolves to changes.")
    ResponseEntity<RotatedClientResponse> rotate(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @PathVariable UUID clientId,
            @RequestParam int expectedVersion,
            @Valid @RequestBody RotatePartnerClientRequest request) {

        PartnerApiClientService.IssuedSecret rotated = clients.rotate(
                tenantId,
                clientId,
                expectedVersion,
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return ResponseEntity.ok(new RotatedClientResponse(
                rotated.id(), rotated.secretValue(), rotated.secretExpiresAt(), rotated.version()));
    }

    @DeleteMapping("/{clientId}")
    @RequiresCapability(value = Capability.PARTNER_API_CLIENT_MANAGE, mutating = true)
    @Operation(
            summary = "Revoke a partner API client",
            description = "Disables the Keycloak client so no new token can ever be minted for it, then "
                    + "retires the row. There is no DELETED status (ADR 0040): the record that a "
                    + "credential once existed survives, only its authority does not.")
    ResponseEntity<Map<String, Object>> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @PathVariable UUID clientId,
            @RequestParam int expectedVersion,
            @Valid @RequestBody RevokePartnerClientRequest request) {

        boolean revoked = clients.revoke(
                tenantId,
                clientId,
                expectedVersion,
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return ResponseEntity.ok(Map.of("changed", revoked, "outcome", revoked ? "revoked" : "no_change"));
    }

    public record IssuePartnerClientRequest(
            @NotBlank @Size(max = 255) String displayLabel,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RotatePartnerClientRequest(
            @NotBlank @Size(max = 1000) String reason) {}

    public record RevokePartnerClientRequest(
            @NotBlank @Size(max = 1000) String reason) {}

    /** Carries the plaintext secret. Returned exactly once, from {@code issue} alone. */
    public record IssuedClientResponse(
            UUID id,
            String clientId,
            String secretValue,
            @Nullable Instant secretExpiresAt,
            int version) {}

    /** Carries the plaintext secret. Returned exactly once, from {@code rotate} alone. */
    public record RotatedClientResponse(
            UUID id, String secretValue, @Nullable Instant secretExpiresAt, int version) {}

    /** Never the secret value or the ADR 0028 reference to it — see the class doc comment. */
    public record PartnerClientResponse(
            UUID id,
            String clientId,
            String status,
            boolean secretConfigured,
            @Nullable Instant secretRotatedAt,
            @Nullable Instant secretExpiresAt,
            @Nullable Instant lastAuthenticatedAt,
            int version) {

        static PartnerClientResponse of(JdbcPartnerStore.PartnerClientView view) {
            return new PartnerClientResponse(
                    view.id(),
                    view.clientId(),
                    view.status(),
                    view.secretReference() != null,
                    view.secretRotatedAt(),
                    view.secretExpiresAt(),
                    view.lastAuthenticatedAt(),
                    view.version());
        }
    }
}
