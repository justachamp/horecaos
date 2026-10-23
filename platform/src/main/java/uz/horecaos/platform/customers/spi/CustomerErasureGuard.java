package uz.horecaos.platform.customers.spi;

import java.util.List;
import java.util.UUID;

/**
 * A module's reason why a customer's erasure has to wait (ADR 0029: "refuse while a dispute or a delivery is
 * open").
 *
 * <p>The sibling of {@link CustomerErasureParticipant} on the question that comes first. Customers owns the
 * request and the legal holds; it does not own the orders a customer still expects, or the service-recovery
 * case a customer still has open, so each module that does reports them here and {@code
 * CustomerErasureService.execute} refuses while any guard answers. Customers never imports those modules; they
 * register a bean of this type and customers discovers it, the direction the participant seam already takes.
 *
 * <p>A guard answers with codes and a count, never a name, a number or an order id: the refusal is returned to
 * the operator and written to the audit trail, and ADR 0029 keeps personal data out of both.
 */
public interface CustomerErasureGuard {

    /**
     * What stands between this customer and an erasure, in this module's keeping. Empty when nothing does.
     *
     * <p>Read-only and cheap: called on every execute, inside its transaction.
     */
    List<Block> blocksFor(UUID tenantId, UUID customerAccountId);

    /**
     * One reason to wait.
     *
     * @param code   a stable, upper-case code the console maps to words ({@code OPEN_ORDER})
     * @param amount how many things stand in the way (open orders, open cases); at least 1
     */
    record Block(String code, int amount) {

        public Block {
            if (code.isBlank() || amount < 1) {
                throw new IllegalArgumentException("A block names a code and at least one thing in the way");
            }
        }
    }
}
