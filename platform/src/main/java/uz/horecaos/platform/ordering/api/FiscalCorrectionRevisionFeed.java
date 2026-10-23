package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read the fiscal module needs to learn that an order was amended after its receipt was filed (ADR 0039,
 * ADR 0166 Decision 8).
 *
 * <p>Every revision an amendment appends carries a flag saying whether ADR 0039's consequence matrix marks its
 * command «correction if a receipt exists». The flag has been written since V0395 and, until this port, read by
 * nothing. Ordering answers with the flagged revisions and nothing else of the order: no lines, no money beyond
 * the signed change, no customer. Whether a receipt exists, and whether it left before the revision, is fiscal's
 * to decide from its own documents.
 *
 * <p>A platform read, like the obligation opener's: it belongs to no tenant, because a tenant predicate would
 * mean the reader only covers the tenants somebody remembered to enumerate.
 */
public interface FiscalCorrectionRevisionFeed {

    /**
     * Revisions created at or after {@code since} whose command declares a fiscal consequence, oldest first.
     *
     * <p>Only an amendment's revision is ever flagged: revision 1, the checkout snapshot, is not.
     *
     * @param limit the most revisions to return
     */
    List<FlaggedRevision> flaggedSince(Instant since, int limit);

    /**
     * @param revision the order's revision number, at least 2
     * @param createdAt when the revision was appended, which is when the amended order took effect
     * @param deltaTotalMinor the signed change of the order total against the previous revision
     */
    record FlaggedRevision(UUID tenantId, UUID orderId, int revision, Instant createdAt, long deltaTotalMinor) {}
}
