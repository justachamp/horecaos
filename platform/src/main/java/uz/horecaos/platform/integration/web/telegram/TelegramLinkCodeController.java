package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
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

    public TelegramLinkCodeController(TelegramLinkService links, CurrentActor currentActor) {
        this.links = links;
        this.currentActor = currentActor;
    }

    @PostMapping("/link-codes")
    @RequiresCapability(value = Capability.INTEGRATION_TELEGRAM_LINK_ISSUE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Issue a Telegram group-link code",
            description = "Paste \"/link <code>\" into the target group. The code expires shortly and "
                    + "is spent the moment the bot's rights are verified and the binding is created; "
                    + "a null locationId scopes the binding to the whole brand rather than one branch.")
    public ResponseEntity<Map<String, Object>> issue(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) UUID locationId) {

        String code = links.issueCode(tenantId, brandId, locationId, currentActor.get().subject());
        return ResponseEntity.ok(Map.of("code", code, "command", "/link " + code));
    }
}
