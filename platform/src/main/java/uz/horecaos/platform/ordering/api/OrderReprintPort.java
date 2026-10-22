package uz.horecaos.platform.ordering.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Whether an order's branch can put a document on paper right now, for the board's {@code REPRINT} action
 * (orders.md §2.12's {@code p}, ADR 0154).
 *
 * <p>Declared in {@code ordering}'s {@code api} and implemented by {@code printing}, which already depends on
 * {@code ordering}: a port the other way round would be a cycle. Asked once for the branches of a page, never
 * per row.
 *
 * <p>Defaults to "none" so a build with no printing module offers no reprint, which is also the truth there.
 */
public interface OrderReprintPort {

    /**
     * Which of these branches of the brand can print an order's document: {@code feature.printing} (ADR 0082)
     * is on for the tenant at that branch, and at least one active printer is registered there. A branch absent
     * from the result has printing off or nothing to print on, and its orders carry no {@code REPRINT}.
     */
    default Set<UUID> branchesThatCanPrint(UUID tenantId, UUID brandId, Collection<UUID> locationIds) {
        return Set.of();
    }
}
