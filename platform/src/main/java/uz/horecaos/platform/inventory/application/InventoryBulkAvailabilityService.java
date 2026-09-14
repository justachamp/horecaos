package uz.horecaos.platform.inventory.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The stop list's batch stop/unstop (gap map row 2.5, wave P16), modelled on
 * ADR 0039's own bulk contract — {@code OrderBulkActionService}'s doc is the
 * fuller statement of the shape this mirrors: every item is applied and
 * recorded through its own call into the ordinary single-item service ({@link
 * InventoryService#setAvailabilityAudited}), each of which opens its own
 * transaction because this class, deliberately, opens none. One already-failed
 * variant must never roll back the other hundred and ninety-nine, the same
 * argument that ADR 0039 rejects an all-or-nothing bulk transaction for.
 *
 * <p>Replaces {@code stop-list-page.ts}'s own sequential loop of one audited
 * `PUT` per row — a 300-item stop used to be three hundred round trips from
 * the browser with a partial-failure count as the only report. This is the
 * one round trip that loop was missing, not a new mutation: every item still
 * lands through the identical audited call the single-row toggle already
 * uses, so the ADR 0027 trail an operator reads is unchanged in shape, only
 * in how many round trips it took to produce.
 *
 * <p>Unlike {@code OrderBulkActionService}, this needs no {@code
 * bulk_operations}/{@code bulk_operation_items} ledger of its own for replay
 * safety: the whole endpoint already carries ADR 0031's {@code
 * Idempotency-Key} protection ({@code @RequiresCapability(mutating = true)}
 * is enough to arm {@code IdempotencyInterceptor}), and {@link
 * InventoryService#setAvailability}'s own no-op rule — a variant already in
 * its target state changes nothing and is not re-audited — makes a replayed
 * item idempotent on its own terms even before the interceptor's cached
 * response would ever be reached.
 */
@Service
public class InventoryBulkAvailabilityService {

    /** ADR 0039's own cap, reused rather than re-derived. */
    public static final int MAX_ITEMS = 200;

    private static final Logger log = LoggerFactory.getLogger(InventoryBulkAvailabilityService.class);

    private final InventoryService inventory;

    public InventoryBulkAvailabilityService(InventoryService inventory) {
        this.inventory = inventory;
    }

    public List<ItemOutcome> apply(
            UUID tenantId,
            UUID locationId,
            List<UUID> variantIds,
            boolean available,
            String reasonCode,
            String actorSubject) {
        if (variantIds.isEmpty()) {
            throw new IllegalArgumentException("A batch names at least one variant");
        }
        if (variantIds.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("A batch names at most " + MAX_ITEMS + " variants");
        }

        List<ItemOutcome> outcomes = new ArrayList<>(variantIds.size());
        for (UUID variantId : variantIds) {
            outcomes.add(applyOne(tenantId, locationId, variantId, available, reasonCode, actorSubject));
        }
        return outcomes;
    }

    /**
     * One variant, its own outcome. Nothing here shares a transaction with
     * another item — {@link InventoryService#setAvailabilityAudited} is
     * called as an ordinary bean method through the injected proxy, so it
     * opens and settles its own transaction, and a failure here can never
     * roll back a neighbour's success.
     */
    private ItemOutcome applyOne(
            UUID tenantId, UUID locationId, UUID variantId, boolean available, String reasonCode, String actorSubject) {
        try {
            boolean changed = inventory.setAvailabilityAudited(
                    tenantId, locationId, variantId, available, reasonCode, actorSubject);
            return new ItemOutcome(variantId, ItemStatus.APPLIED, changed, null);
        } catch (IllegalArgumentException notStocked) {
            return new ItemOutcome(variantId, ItemStatus.FAILED, false, "VARIANT_NOT_STOCKED");
        } catch (IllegalStateException wrongTrackingMode) {
            return new ItemOutcome(variantId, ItemStatus.FAILED, false, "UNSUPPORTED_TRACKING_MODE");
        } catch (RuntimeException unexpected) {
            // One variant's unexpected failure must never stop the rest of the
            // batch, and must never be reported as a silent success either.
            log.error(
                    "Bulk availability item {} at location {} failed unexpectedly", variantId, locationId, unexpected);
            return new ItemOutcome(variantId, ItemStatus.FAILED, false, "UNEXPECTED_FAILURE");
        }
    }

    public enum ItemStatus {
        APPLIED,
        FAILED
    }

    /**
     * @param changed whether this variant's state actually flipped — false
     *                for an {@code APPLIED} outcome means it was already in
     *                the target state, matching {@link
     *                InventoryService#setAvailability}'s own no-op rule
     */
    public record ItemOutcome(
            UUID variantId,
            ItemStatus status,
            boolean changed,
            @Nullable String problemCode) {}
}
