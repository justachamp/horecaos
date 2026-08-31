package uz.horecaos.platform.notifications.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;

/**
 * Whether a tenant's plan includes the Telegram feature family — ADR 0058's
 * own open input, resolved 2026-08-31: "entitlement-gated from day one".
 *
 * <p><strong>A local seam, not the canonical key.</strong> ADR 0058 hands the
 * {@code telegram.*} entitlement key family to the sibling wave6-digests
 * build (the digest scheduler is the family's other caller). That worktree's
 * {@code EntitlementKeys} addition was not visible from this one — both
 * branch from the same commit and, as of this class being written, neither
 * had merged — so rather than guess its exact code string and risk a
 * duplicate or a diverging declaration in the shared catalogue, this
 * declares its own key locally.
 *
 * <p><strong>Merge point:</strong> when wave6-digests's {@code telegram.*}
 * key lands in {@link uz.horecaos.platform.commercial.api.EntitlementKeys},
 * delete {@link #TELEGRAM_OPERATIONS_ENABLED} below and change {@link
 * #enabledFor} to resolve the canonical key instead. Every operations
 * trigger listener calls only {@link #enabledFor}, never {@code
 * EntitlementService} directly, so that is a one-file change.
 *
 * <p>Feature keys resolve to their {@code safeDefault} — {@code TRUE} here —
 * for a tenant with no subscription at all (ADR 0021's rule that an
 * unsubscribed tenant is an unfinished sale, never a tenant that stops
 * working), so wiring this gate in changes nothing for every tenant this
 * build's fixtures and tests already exercise; it only starts to matter once
 * a plan or an override explicitly disables the family.
 */
@Component
public class TelegramOperationsEntitlementGate {

    /**
     * Provisional code, dotted lower case per {@link EntitlementKey}'s own
     * validation rule. Deliberately not added to {@code EntitlementKeys}'s
     * registry: {@link EntitlementService#featureEnabled} resolves a key from
     * the fields on the key object itself and never consults that registry,
     * so an unregistered key still works for this call — it simply will not
     * appear in a tenant's {@code snapshot()} until the merge above
     * registers the real one.
     */
    static final EntitlementKey<Boolean> TELEGRAM_OPERATIONS_ENABLED = EntitlementKey.feature(
                    "telegram.operations_alerts.enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("notifications")
            .describedAs("Whether operations Telegram alerts may fan out to this tenant's bound chats (ADR 0058).")
            .build();

    private final EntitlementService entitlements;

    public TelegramOperationsEntitlementGate(EntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Whether {@code tenantId}'s plan includes the Telegram operations alert family. */
    public boolean enabledFor(UUID tenantId) {
        return entitlements.featureEnabled(tenantId, TELEGRAM_OPERATIONS_ENABLED);
    }
}
