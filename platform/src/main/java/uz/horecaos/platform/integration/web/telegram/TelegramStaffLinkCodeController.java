package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.provider.telegram.TelegramStaffLinkService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Issues the short opaque code a staff member pastes as {@code /link <code>}
 * in a 1:1 chat with the bot to bind their own Telegram account to their own
 * principal (ADR 0060 §3).
 *
 * <p>Self-service by construction: the code is minted for {@code
 * currentActor}, never for a subject the caller names, so holding the
 * capability lets a staff member link only themselves — there is no "link
 * someone else's account" operation. That is why the capability is granted
 * broadly across the front-line role bundles rather than reserved to an
 * administrator.
 *
 * <p><strong>Two routes issue the code, because a grant serves only the routes
 * whose path names its level</strong> (ADR 0025: a scope covers downward,
 * never upward or sideways). The capability is carried by the {@code
 * brand-manager}, {@code location-manager} and {@code location-staff} bundles
 * at <em>their own</em> scope, so the original {@code TENANT}-scope route
 * refused every one of them — a line cook holding {@code location-staff} at
 * one branch could not mint the code the bundle exists to let them mint.
 * {@link #issueAtLocation} names the caller's branch in its path and so
 * resolves the capability at {@code LOCATION} scope, which a location grant
 * covers exactly and a brand or tenant grant covers from above; {@link
 * #issue} stays for a tenant-wide holder with no branch to name. The location
 * chosen decides only which grants count and where the audit fact lands, never
 * whose account is linked: the subject is always {@code currentActor}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@Tag(name = "Telegram staff linking", description = "ADR 0060 section 3: the staff identity /link handshake")
public class TelegramStaffLinkCodeController {

    private final TelegramStaffLinkService links;
    private final CurrentActor currentActor;
    private final AuditRecorder audit;
    private final Clock clock;

    public TelegramStaffLinkCodeController(
            TelegramStaffLinkService links, CurrentActor currentActor, AuditRecorder audit, Clock clock) {
        this.links = links;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping("/staff/telegram/link-codes")
    @RequiresCapability(
            value = Capability.INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE,
            scope = ScopeType.TENANT,
            mutating = true)
    @Operation(
            summary = "Issue a staff Telegram identity-link code",
            description = "Send \"/link <code>\" to the bot in a 1:1 chat. Binds the caller's own "
                    + "Telegram account to the caller's own principal in this tenant; a Telegram "
                    + "account may hold one such link per tenant, and many tenants at once. "
                    + "Tenant-scope: satisfied by a tenant-wide grant only. A brand or branch "
                    + "member uses the branch-scoped route instead.")
    public ResponseEntity<LinkCodeResponse> issue(@PathVariable UUID tenantId) {
        return issueFor(tenantId, ResourceScope.tenant(tenantId));
    }

    @PostMapping("/brands/{brandId}/locations/{locationId}/staff/telegram/link-codes")
    @RequiresCapability(
            value = Capability.INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE,
            scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(
            summary = "Issue a staff Telegram identity-link code from a branch",
            description = "The same self-service code as the tenant-scope route, for a brand or "
                    + "branch member: name the branch you work at and the capability is checked "
                    + "there. A location grant covers only its own branch, so naming another "
                    + "branch is refused; a brand or tenant grant covers the branches beneath it. "
                    + "The code always binds the caller's own account, whatever branch is named.")
    public ResponseEntity<LinkCodeResponse> issueAtLocation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return issueFor(tenantId, ResourceScope.location(tenantId, brandId, locationId));
    }

    private ResponseEntity<LinkCodeResponse> issueFor(UUID tenantId, ResourceScope auditScope) {
        String subject = currentActor.get().subject();
        String code = links.issueCode(tenantId, subject);

        audit.record(AuditFact.of("integration.telegram_staff_link_code_issued", AuditClass.SECURITY)
                .by(ActorRef.user(subject, null))
                .at(auditScope)
                .because("Issued a staff Telegram identity-link code")
                .usingCapability(Capability.INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE.code())
                .correlatedBy(tenantId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(new LinkCodeResponse(code, "/link " + code));
    }

    /**
     * A record rather than a raw {@code Map<String, Object>} so {@code
     * IdempotentResponseClassificationTests} can classify this response by
     * reflection like every other typed endpoint — neither field is personal
     * data, but a map answers that by convention, not by a type the scanner
     * can read.
     */
    public record LinkCodeResponse(String code, String command) {}

    /**
     * Every staff Telegram link in the tenant — administrative, not
     * self-service, so it is gated on {@link Capability#IAM_GRANT_MANAGE}
     * rather than the broadly-held issue capability above: a manager reading
     * whether Aziza has linked her account is a staff-administration question
     * (staff-and-access.md §9.1's People screen and §9.2's Безопасность tab),
     * not something the self-link capability was ever meant to expose.
     */
    @GetMapping("/staff/telegram/links")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.TENANT)
    @Operation(
            summary = "List staff Telegram links in the tenant",
            description = "Which principal each linked Telegram account acts as. No display name or "
                    + "username is stored (V0105) — only the numeric Telegram user id — so this is "
                    + "linked/not-linked evidence, not an identity lookup.")
    public List<TelegramStaffLinkService.StaffLinkView> listLinks(@PathVariable UUID tenantId) {
        return links.listForTenant(tenantId);
    }

    /**
     * «Unlink Aziza» — an administrator severs a staff member's Telegram
     * identity link. Gated the same as {@link #listLinks} rather than the
     * broadly-held issue capability above: unlinking someone else's account is
     * exactly the administrative action {@link #issue}'s self-service design
     * deliberately has no operation for.
     *
     * <p>Deletes the identity fact outright ({@link TelegramStaffLinkService
     * #revoke}'s own doc explains why) — this never touches {@code
     * iam.grants}, and a staff member who still holds a grant keeps every bit
     * of it; only the Telegram binding is gone, and she may link a new (or the
     * same) account again with a fresh code.
     */
    @DeleteMapping("/staff/telegram/links/{linkId}")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Unlink a staff Telegram identity link",
            description = "Removes the binding between a Telegram account and the principal it acts "
                    + "as in this tenant. Does not touch iam.grants — a staff member's authority is "
                    + "unaffected; she may link again with a fresh code.")
    public ResponseEntity<UnlinkResponse> unlink(
            @PathVariable UUID tenantId, @PathVariable UUID linkId, @Valid @RequestBody ReasonRequest request) {
        boolean changed = links.revoke(tenantId, linkId);

        audit.record(AuditFact.of("integration.telegram_staff_link_revoked", AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .because(request.reason())
                .usingCapability(Capability.IAM_GRANT_MANAGE.code())
                .correlatedBy(linkId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(new UnlinkResponse(changed, changed ? "revoked" : "no_change"));
    }

    /** Mirrors the {@code {reason}} shape every other revoke-style endpoint in this codebase takes. */
    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    /** Neither field is personal data — a link id and a fixed outcome string. */
    public record UnlinkResponse(boolean changed, String outcome) {}
}
