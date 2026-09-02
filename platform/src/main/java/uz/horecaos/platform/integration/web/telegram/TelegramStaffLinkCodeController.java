package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/staff/telegram")
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

    @PostMapping("/link-codes")
    @RequiresCapability(
            value = Capability.INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE,
            scope = ScopeType.TENANT,
            mutating = true)
    @Operation(
            summary = "Issue a staff Telegram identity-link code",
            description = "Send \"/link <code>\" to the bot in a 1:1 chat. Binds the caller's own "
                    + "Telegram account to the caller's own principal in this tenant; a Telegram "
                    + "account may hold one such link per tenant, and many tenants at once.")
    public ResponseEntity<LinkCodeResponse> issue(@PathVariable UUID tenantId) {
        String subject = currentActor.get().subject();
        String code = links.issueCode(tenantId, subject);

        audit.record(AuditFact.of("integration.telegram_staff_link_code_issued", AuditClass.SECURITY)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.tenant(tenantId))
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
    @GetMapping("/links")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.TENANT)
    @Operation(
            summary = "List staff Telegram links in the tenant",
            description = "Which principal each linked Telegram account acts as. No display name or "
                    + "username is stored (V0105) — only the numeric Telegram user id — so this is "
                    + "linked/not-linked evidence, not an identity lookup.")
    public List<TelegramStaffLinkService.StaffLinkView> listLinks(@PathVariable UUID tenantId) {
        return links.listForTenant(tenantId);
    }
}
