package uz.horecaos.platform.notifications.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;

/**
 * Whether a tenant's plan includes the Telegram feature family — ADR 0058's
 * own open input, resolved 2026-08-31: "entitlement-gated from day one".
 *
 * <p>Resolves the canonical {@link EntitlementKeys#TELEGRAM_OPERATIONS_ALERTS_ENABLED}
 * key. This class began life declaring a provisional local key while the
 * wave-6 worktrees could not see each other's {@code EntitlementKeys}
 * additions; the merge registered the real key in the shared catalogue and
 * this gate now simply names it. It stays as a seam because every operations
 * trigger listener calls only {@link #enabledFor}, never
 * {@code EntitlementService} directly.
 *
 * <p>Feature keys resolve to their {@code safeDefault} — {@code TRUE} here —
 * for a tenant with no subscription at all (ADR 0021's rule that an
 * unsubscribed tenant is an unfinished sale, never a tenant that stops
 * working); the gate only starts to matter once a plan or an override
 * explicitly disables the family. The opt-in digests key is deliberately a
 * different registration with the opposite default — see both keys' own
 * doc comments.
 */
@Component
public class TelegramOperationsEntitlementGate {

    private final EntitlementService entitlements;

    public TelegramOperationsEntitlementGate(EntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Whether {@code tenantId}'s plan includes the Telegram operations alert family. */
    public boolean enabledFor(UUID tenantId) {
        return entitlements.featureEnabled(tenantId, EntitlementKeys.TELEGRAM_OPERATIONS_ALERTS_ENABLED);
    }
}
