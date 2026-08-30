package uz.qoida.platform.pricing.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What checkout needs from pricing (ADR 0018, consumed by ADR 0019).
 *
 * <p>Two operations and no more. Ordering may accept a quote and read what was
 * priced; it may not reach the price books, the tax profiles, or the engine.
 * Acceptance stays behind this port precisely because it is the conditional
 * update that decides a race — reimplementing it in ordering would create a
 * second answer to "was this quote already used", and two answers to that
 * question is a customer charged twice.
 *
 * <p>Every method takes the tenant id and every implementation puts it in the
 * query. A quote id is a UUID a caller may have received from anywhere.
 */
public interface QuoteAcceptancePort {

    /**
     * Accepts a quote at checkout, but only while it is still active, unexpired,
     * and hashing to {@code expectedContextHash}.
     *
     * <p>The check and the write are one statement, so two concurrent checkouts
     * cannot both observe an active quote and both proceed.
     */
    QuoteAcceptance acceptQuote(UUID tenantId, UUID quoteId, String expectedContextHash);

    /**
     * The priced result, with the lines and adjustments an order snapshots.
     *
     * @return empty when no such quote exists for this tenant, which is the same
     *         answer a quote belonging to another tenant gets
     */
    Optional<QuoteSnapshot> quoteSnapshot(UUID tenantId, UUID quoteId);
}
