package uz.horecaos.platform.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wires {@link InventoryService#expireStaleReservations()} to a clock instead of
 * to luck (ADR 0017).
 *
 * <p>Before this class, a lapsed hold was only noticed when its own quote was
 * re-reserved — {@code reserveForQuote}'s own idempotency check reads the
 * existing row and, finding it {@code HELD} but past {@code expires_at}, refuses
 * it as a lapsed hold rather than reusing it (see that method's comment). That
 * discovery is real but passive: a cart abandoned after taking a hold, whose
 * customer never comes back, leaves the stock reserved against nobody until some
 * unrelated request happens to touch the same quote — which, for an abandoned
 * cart, is never. Every minute in between is a unit of stock a different
 * customer cannot buy even though the kitchen has it, for a hold that has
 * already expired by its own contract.
 *
 * <p>Same shape as {@link uz.horecaos.platform.loyalty.application.LoyaltySweeper}'s
 * {@code releaseStaleHolds}: a frequent, best-effort, log-and-continue pass,
 * because the thing it delays — a competing customer being able to buy — is
 * measured in minutes, not hours, and a wedged sweep must not take the outbox
 * relay or any other module's timer down with it (see {@code
 * SchedulingConfiguration}'s error handler, which this relies on rather than
 * duplicates).
 *
 * <p>Carries no tenant loop and no per-tenant scheduling of its own:
 * {@link InventoryService#expireStaleReservations()} is already the whole
 * cross-tenant sweep, one {@code UPDATE} across every tenant's stale holds
 * through {@code horecaos_platform_bypass} (see that method's own doc and V0162).
 * Calling it once per tick is calling it correctly; wrapping it in a
 * per-tenant loop here would be a second, redundant way to reach the same rows
 * and a second place the ADR 0056 exemption would have to be re-justified.
 *
 * <p>The switch exists for the same reason {@link
 * uz.horecaos.platform.loyalty.application.LoyaltySweeper}'s does: a one-shot
 * process — a Flyway container, a rehearsal, a console run against a restored
 * copy — must not expire a real tenant's holds as a side effect of starting up.
 */
@Component
@ConditionalOnProperty(name = "horecaos.inventory.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationSweeper.class);

    private final InventoryService inventory;

    public InventoryReservationSweeper(InventoryService inventory) {
        this.inventory = inventory;
    }

    /**
     * Frequent, matching {@code LoyaltySweeper.releaseStaleHolds}'s own reasoning:
     * the customer this delays is not the one who abandoned the cart but the next
     * one who wants the same dish, and that second attempt is usually minutes
     * away, not hours.
     */
    @Scheduled(
            initialDelayString = "${horecaos.inventory.sweeper.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.inventory.sweeper.interval:PT2M}")
    public void expireStaleReservations() {
        try {
            int expired = inventory.expireStaleReservations();
            if (expired > 0) {
                log.info("Inventory reservation sweep expired {} stale holds", expired);
            }
        } catch (RuntimeException failure) {
            // Logged and swallowed, not rethrown: the next tick retries, and the
            // alternative is a dead scheduler that also stops every other
            // module's timer sharing this pool (SchedulingConfiguration).
            log.error("Inventory reservation sweep failed", failure);
        }
    }
}
