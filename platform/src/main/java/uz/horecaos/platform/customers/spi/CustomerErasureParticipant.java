package uz.horecaos.platform.customers.spi;

import java.util.UUID;

/**
 * The seam another module's own erasure operation is called through (ADR 0029,
 * ADR 0044).
 *
 * <p>Customers owns the account and the request that governs erasure, but it
 * does not own every table that carries a copy of a customer's identity or
 * their behaviour. ADR 0044 names two such operations directly —
 * {@code CustomerMetricProjectionService.erase} and
 * {@code JdbcAudienceStore.eraseMembership} in {@code marketing} — and records
 * that they "exist and are tested" but that "nothing in the platform ever
 * produces the fact a sweep would consume". This interface is that missing
 * production point on the customers side: {@code CustomerErasureService}
 * calls every registered participant, in the same transaction as its own
 * anonymisation, whenever a request is executed.
 *
 * <p><strong>Customers depends on nothing here; other modules depend on this.</strong>
 * A module that keeps a copy of customer identity or behaviour — {@code
 * marketing}'s projection and audience snapshots are the first candidates —
 * registers a bean implementing this interface. Customers never imports
 * {@code marketing}, which would invert the module boundary ADR 0044 itself
 * draws ("marketing consumes customer.api"); marketing instead provides an
 * adapter that customers discovers by type, the same direction {@code
 * customers.spi.VerificationCodeTransport} already uses for the SMS adapter
 * customers does not own.
 *
 * <p><strong>No implementation ships with this wave.</strong> Wiring {@code
 * marketing}'s two operations to this interface is that module's own file to
 * touch, deliberately left to the wave that owns it. Until one exists, {@code
 * CustomerErasureService} collects an empty {@code List<CustomerErasureParticipant>} —
 * Spring supplies an empty list rather than failing when no bean implements an
 * interface collected this way, so an erasure with zero participants registered
 * completes exactly as it does today: it erases what {@code customers} itself
 * owns and calls nothing further. That is a known, visible gap rather than a
 * silent one — see this record's own ADR 0029/ADR 0044 status update.
 *
 * <p>Every participant is called inside the same database transaction as the
 * request's own transition to {@code COMPLETED}, so a participant that throws
 * rolls the whole execution back rather than leaving the account anonymised
 * while a projection row survives it.
 */
public interface CustomerErasureParticipant {

    /**
     * Erases or anonymises whatever this participant's own module holds for one
     * customer.
     *
     * <p>Never called outside a transaction already bound to {@code tenantId}.
     * Implementations should be idempotent under retry, the same way {@code
     * CustomerErasureService} itself is: an execution that failed after some
     * participants ran and is retried will call every participant again.
     */
    void erase(UUID tenantId, UUID customerAccountId);
}
