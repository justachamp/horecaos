package uz.horecaos.platform.fiscal.application;

import java.util.Optional;
import java.util.UUID;

/**
 * How many marking codes an order needs and has, as counts and nothing else (ADR 0166 Decision 7,
 * ADR 0161).
 *
 * <p>The per-unit mark store is ADR 0161's ({@code ordering.order_line_marks}) and is read through
 * its {@code OrderMarksPort.markCounts}. This is the evidence surface's own seam to it, declared
 * here by the consumer, which is the codebase's house pattern for a dependency whose producer is
 * somebody else's wave ({@code PartnerFiscalizationPort} is the same shape). Until the port
 * exists, {@link FiscalPortConfiguration} supplies an implementation that says so and the
 * evidence block reads «marks not built» with no count; when it exists, one small adapter
 * delegating to it replaces that.
 *
 * <p><strong>A count, never a code, never a tail.</strong> Nothing that could identify a mark is
 * on this interface, so nothing on it can reach a response.
 */
public interface OrderMarkCounts {

    /**
     * @return the order's totals, or empty when this deployment has no mark store at all (which is
     *     different from an order that needs no marks: that answers {@code 0 of 0})
     */
    Optional<Totals> forOrder(UUID tenantId, UUID orderId);

    /**
     * @param required marked units the order's lines need a code for
     * @param captured of those, how many hold a live captured code
     */
    record Totals(int required, int captured) {

        public Totals {
            if (required < 0 || captured < 0 || captured > required) {
                throw new IllegalArgumentException("Captured marks are between none and all that are required");
            }
        }
    }
}
