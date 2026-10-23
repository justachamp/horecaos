package uz.horecaos.platform.ordering.application;

import java.util.List;
import java.util.UUID;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;

/**
 * Whether a basket forces a payment method that can carry a marking code (ADR 0038 stage 6, ADR 0166
 * Decision 7, precondition P1 of ADR 0161).
 *
 * <p>Click's receipt line has a {@code Labels} array; Payme's {@code detail} object has no field for a
 * marking code anywhere. A marked good therefore cannot lawfully be fiscalized through Payme, and the
 * place to say so is the list of methods a customer is offered, while the cart is still being priced: an
 * adapter learns about the basket when it is asked to take money, which is after the customer chose, and a
 * refusal there is a failed checkout and not a button that was never shown. The fact is the catalog's
 * ({@link FiscalNodeFacts}); this reads it for the nodes a basket holds.
 *
 * <p>Gated by the same flag as the capture of marks. A tenant that has not switched marked goods on
 * captures no marks, files no codes and keeps every method it had; the exclusion arrives with the feature
 * and not before, which is the order ADR 0161 asks for (this wiring lands before the flag is turned on for
 * anyone, and takes effect when it is).
 */
public interface MarkedCartLines {

    /**
     * @param lines the basket's lines; the variants and every modifier option they carry are the nodes asked
     *     about
     */
    boolean requiresMarkingCapablePayment(UUID tenantId, UUID brandId, List<CartLineRow> lines);

    /** A deployment, or a suite, with nothing marked: every basket may use every method. */
    MarkedCartLines NONE = (tenantId, brandId, lines) -> false;
}
