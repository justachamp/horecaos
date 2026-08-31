package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One typed answer to "what may be sold here, right now, through this route"
 * (ADR 0036).
 *
 * <p>Browse, quoting and ADR 0019 checkout all read this same record from the
 * same resolver. There is deliberately no boolean anyone can flip out of band:
 * an {@code is_open} column cannot say when service resumes, cannot differ per
 * fulfilment mode, and one failed job silently closes a network with a cause
 * indistinguishable from an outage.
 *
 * @param available            whether an order may be placed for immediate service
 * @param reason               why not, absent when {@code available}
 * @param nextAvailableAt      when service resumes, where that is computable
 * @param acceptsScheduledOrders whether a pre-order may still be taken while
 *                             closed — "closed now" and "cannot pre-order" are
 *                             different facts
 * @param preparationMinutes   the band-derived preparation estimate, absent when
 *                             no band covers this instant
 */
public record Serviceability(
        boolean available,
        @Nullable ServiceabilityReason reason,
        @Nullable Instant nextAvailableAt,
        boolean acceptsScheduledOrders,
        @Nullable Integer preparationMinutes) {

    public Serviceability {
        if (available && reason != null) {
            throw new IllegalArgumentException("An available location has no refusal reason");
        }
        if (!available) {
            Objects.requireNonNull(reason, "An unavailable location must say why");
        }
    }

    public static Serviceability available(boolean acceptsScheduledOrders, @Nullable Integer preparationMinutes) {
        return new Serviceability(true, null, null, acceptsScheduledOrders, preparationMinutes);
    }

    public static Serviceability refused(
            ServiceabilityReason reason, @Nullable Instant nextAvailableAt, boolean acceptsScheduledOrders) {
        return new Serviceability(false, reason, nextAvailableAt, acceptsScheduledOrders, null);
    }

    public Optional<Instant> nextAvailable() {
        return Optional.ofNullable(nextAvailableAt);
    }
}
