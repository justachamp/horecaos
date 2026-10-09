package uz.horecaos.platform.customers.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a module contributes to one guest's card (ADR 0111 §2, §8).
 *
 * <p><strong>Declared here, implemented by the owner of the data.</strong> ADR 0111 names the three
 * ports {@code notifications.api.CustomerCommunicationHistoryPort}, {@code
 * marketing.api.CustomerEngagementHistoryPort} and {@code reviews.api.CustomerReviewPort.history},
 * each read by {@code customers}. {@code notifications}, {@code marketing} and {@code reviews} all
 * import {@code customers.api} already (recipient resolution, eligibility, ownership), so a
 * {@code customers -> notifications} edge would close a cycle the module verifier refuses -- the
 * reason {@code CustomerOrderActivityPort} is declared on this side and implemented by {@code
 * ordering}. The same inversion is what keeps the decision's rule intact: {@code customers} never
 * queries another module's tables, and no module keeps a private copy of another's data; each owner
 * implements this and answers from its own tables, and the card service collects every bean of the
 * type without importing any of them.
 *
 * <p>Implementations answer for one guest in one tenant, newest first -- by instant, then by entry id,
 * both descending, which is {@link HistoryCursor#newestFirst()} -- at most {@code limit} entries after
 * {@code before} in that order; they never decrypt and never return free text. A page that ends between
 * two entries of one instant must lose neither, which is why the cursor carries the id as well.
 */
public interface CustomerHistorySource {

    /** A stable name for this source, shown by the card when the source could not answer. */
    String name();

    List<CustomerHistoryEntry> history(
            UUID tenantId, UUID customerAccountId, @Nullable HistoryCursor before, int limit);
}
