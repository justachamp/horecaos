package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.provider.telegram.TelegramLinkService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Issues the short opaque code an operator pastes as {@code /link <code>} into
 * the Telegram group they want bound (ADR 0058).
 *
 * <p>Everything else about the handshake happens server-to-server between
 * Telegram and {@code TelegramWebhookController}; this endpoint's only job is to
 * mint a code an authenticated, capable operator is allowed to spend.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/integrations/telegram")
@Tag(name = "Telegram operations linking", description = "ADR 0058 stage 1: operations groups")
public class TelegramLinkCodeController {

    private final TelegramLinkService links;
    private final CurrentActor currentActor;
    private final AuditRecorder audit;
    private final Clock clock;

    public TelegramLinkCodeController(
            TelegramLinkService links, CurrentActor currentActor, AuditRecorder audit, Clock clock) {
        this.links = links;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping("/link-codes")
    @RequiresCapability(value = Capability.INTEGRATION_TELEGRAM_LINK_ISSUE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Issue a Telegram group-link code",
            description = "Paste \"/link <code>\" into the target group. The code expires shortly and "
                    + "is spent the moment the bot's rights are verified and the binding is created; "
                    + "a null locationId scopes the binding to the whole brand rather than one branch.")
    public ResponseEntity<Map<String, Object>> issue(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @RequestParam(required = false) UUID locationId) {

        String subject = currentActor.get().subject();
        String code = links.issueCode(tenantId, brandId, locationId, subject);

        // ADR 0026: "binding activation and suspension... are ADR 0027 audit
        // facts." Nothing is bound yet — this is the code that will let it be —
        // so the target is the brand rather than a binding id that does not
        // exist yet; the binding's own creation is audited separately when the
        // handshake actually completes.
        audit.record(AuditFact.of("integration.telegram_link_code_issued", AuditClass.SECURITY)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Brand", brandId)
                .because("Issued a Telegram group-link code" + (locationId == null ? "" : " scoped to one location"))
                .usingCapability(Capability.INTEGRATION_TELEGRAM_LINK_ISSUE.code())
                .correlatedBy(brandId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(Map.of("code", code, "command", "/link " + code));
    }
}
