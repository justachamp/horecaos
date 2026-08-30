package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The one answer to "can this be ordered" (ADR 0036).
 *
 * <p>There is no second implementation. Browse reads a short-lived cache of it,
 * quoting calls it, and ADR 0019's checkout re-resolves it from PostgreSQL inside
 * its transaction. The reason a single resolver is a decision rather than a
 * convenience is that the alternative has been tried everywhere: browse says open,
 * checkout says closed, and support cannot reproduce either.
 */
public interface ServiceabilityResolver {

    /**
     * @param at the instant to answer for — passed in rather than read from a
     *           clock inside, so a scheduled order can ask about its own start
     *           time and so a test can ask about 01:00 without waiting for it
     */
    Serviceability resolve(
            UUID tenantId, UUID brandId, UUID locationId, UUID channelId, FulfillmentMode mode, Instant at);
}
