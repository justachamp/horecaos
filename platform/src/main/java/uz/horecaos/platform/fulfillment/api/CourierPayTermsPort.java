package uz.horecaos.platform.fulfillment.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one question {@code fulfillment} asks {@code courier} about pay before it lets a dispatch rule turn
 * grouping on (ADR 0170 Decision 6).
 *
 * <p>Declared here, beside the code that asks, for the reason {@link InternalFleetPort} gives: {@code courier}
 * already depends on {@code fulfillment.api}, and the reverse edge would close a module cycle. {@code courier}
 * owns the {@code courier.compensation} document and answers from it; {@code fulfillment} never reads it, so
 * "how is a courier paid" has one answer.
 */
public interface CourierPayTermsPort {

    /**
     * How a run of several orders is paid at this scope, as the resolved {@code courier.compensation} says.
     *
     * @param brandId    null for a tenant-wide scope
     * @param locationId null for a tenant- or brand-wide scope
     */
    RunPayTerms runPayTerms(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId);

    /** The pay mode a dispatch rule's grouping is judged against. */
    enum RunPayTerms {

        /** The document has not decided: every order is paid in full, and grouping cannot be switched on. */
        UNDECIDED,

        /** Every order in a run is paid in full. */
        PER_ORDER,

        /** The first order of a run is paid as always; later ones pay their per-kilometre part on the shorter distance. */
        FIRST_FULL_THEN_INCREMENT;

        public boolean decided() {
            return this != UNDECIDED;
        }
    }
}
