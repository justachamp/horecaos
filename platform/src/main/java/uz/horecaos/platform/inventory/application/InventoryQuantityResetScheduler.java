package uz.horecaos.platform.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The production caller a QUANTITY item's daily default (gap map row 4.4c,
 * V0405/V0406) never had: without this class, {@code
 * InventoryService#resetDueQuantityItems} is real and tested but nothing ever
 * invokes it, and an item configured with a daily default simply keeps
 * whatever on-hand quantity an operator last set forever.
 *
 * <p>Per-tenant, on the tenant's own business-day clock ({@code
 * InventoryBusinessDayWindowsAdapter}), the same shape {@code
 * DayCloseScheduler} established for ADR 0043: two tenants in different
 * timezones, or one on a non-midnight boundary, cross their own reset moment
 * at different wall-clock times, so this polls every tenant with at least one
 * configured default on one timer rather than enumerating cron expressions
 * per tenant.
 *
 * <p><b>Idempotent per (tenant, stock item, business date).</b> {@link
 * uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore#resetIfDue}
 * re-checks and locks the same "not yet reset for this business date"
 * predicate this scheduler's own worklist query used, in the same statement —
 * a concurrent tick or a second replica racing the identical item resets it
 * at most once, with no separate lease table (see that method's own doc).
 *
 * <p>Not bounded to a catch-up limit the way {@code DayCloseScheduler} is:
 * an item that missed several business days' worth of resets (the platform
 * was down, the tenant's default was only just configured) is due for
 * exactly one reset — "was this ever reset for today's business date" is a
 * point-in-time question, not a range to replay one day at a time the way a
 * day-close fact is.
 */
@Component
@ConditionalOnProperty(name = "horecaos.inventory.quantity-reset.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryQuantityResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryQuantityResetScheduler.class);

    private final InventoryService inventory;
    private final Clock clock;

    public InventoryQuantityResetScheduler(InventoryService inventory, Clock clock) {
        this.inventory = inventory;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${horecaos.inventory.quantity-reset.initial-delay:PT45S}",
            fixedDelayString = "${horecaos.inventory.quantity-reset.interval:PT5M}")
    public void resetDueItems() {
        Instant now = clock.instant();
        for (UUID tenantId : inventory.tenantsWithQuantityDefaults()) {
            try {
                int resetCount = inventory.resetDueQuantityItems(tenantId, now);
                if (resetCount > 0) {
                    log.info("Reset {} QUANTITY item(s) to their daily default for tenant {}", resetCount, tenantId);
                }
            } catch (RuntimeException failure) {
                log.warn("QUANTITY daily reset failed for tenant {}", tenantId, failure);
            }
        }
    }
}
