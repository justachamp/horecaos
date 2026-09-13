package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;

/**
 * Changing many prices in one call, with a dry run (operations gap map row
 * {@code 4.8b}).
 *
 * <p>Composing this client-side as N optimistic-locked {@code PUT
 * .../variant-prices/{variantId}} calls gives no atomicity and no
 * partial-failure story: two hundred requests racing an operator's own
 * concurrent edit, no single report of what actually changed, and no way to
 * see what a 10% increase across a filtered selection would cost before
 * committing it. This is the same shape as {@code ZoneBatchImportService}
 * (ADR 0037's batch geozone import) and {@code OrderBulkActionService}'s
 * {@code POST .../bulk-actions}: each item runs through {@link
 * PriceAuthoringService#setPrice}, the same single-item write authoring a
 * variant's price by hand uses, in its own {@link
 * TransactionDefinition#PROPAGATION_REQUIRES_NEW} transaction — so one bad
 * item (an unknown variant, a negative amount, a book that turned out to be
 * archived) cannot fail the other one hundred and ninety-nine, and cannot
 * roll back what they already committed.
 *
 * <p>{@code dryRun} runs every item's write and then rolls it back, exactly
 * the way the zone batch import's own dry run does: every check a real
 * apply would run — the priceable exists, the amount is not negative, the
 * book is still authorable — has now run for real, and the report is exactly
 * what a committed run would have produced, without anything surviving the
 * transaction.
 */
@Service
public class PriceBulkApplyService {

    private final PriceAuthoringService authoring;
    private final JdbcPricingStore store;
    private final Clock clock;
    private final TransactionTemplate perItem;

    public PriceBulkApplyService(
            PriceAuthoringService authoring, JdbcPricingStore store, Clock clock, TransactionTemplate unitOfWork) {
        this.authoring = authoring;
        this.store = store;
        this.clock = clock;
        this.perItem = new TransactionTemplate(Objects.requireNonNull(
                unitOfWork.getTransactionManager(), "unitOfWork must already carry a transaction manager"));
        this.perItem.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Applies (or previews) every item against one price book.
     *
     * @throws PriceAuthoringService.UnknownPriceBookException if the book does not belong to this brand
     */
    public BulkPriceChangeReport bulkApply(
            UUID tenantId, UUID brandId, UUID priceBookId, List<BulkPriceItem> items, boolean dryRun) {

        // Fails the whole batch fast, with the same 404 a single setPrice call
        // would give, rather than 200 failing items each carrying the same
        // "no such book" problem code.
        authoring.require(tenantId, brandId, priceBookId);

        Instant now = clock.instant();
        Map<UUID, Long> previousAmounts = new HashMap<>();
        for (Map.Entry<PriceableType, Set<UUID>> entry : idsByType(items).entrySet()) {
            previousAmounts.putAll(store.pricesFor(priceBookId, entry.getKey().name(), entry.getValue(), now));
        }

        Set<String> seenInThisBatch = new HashSet<>();
        List<BulkPriceOutcome> outcomes = new ArrayList<>(items.size());
        for (BulkPriceItem item : items) {
            Long previous = previousAmounts.get(item.priceableId());
            String dedupeKey = item.type().name() + ":" + item.priceableId();
            if (!seenInThisBatch.add(dedupeKey)) {
                // Two items in one batch naming the same priceable would each read
                // the same "previous" amount and each report as applied, hiding
                // that the second write actually superseded the first — the same
                // hazard ZoneBatchImportService's own duplicate-code check exists
                // for, and the same fix: reject the second occurrence rather than
                // let a batch silently apply an ambiguous order.
                outcomes.add(BulkPriceOutcome.rejected(
                        item.type(), item.priceableId(), previous, item.amountMinor(), "DUPLICATE_IN_BATCH"));
                continue;
            }

            BulkPriceOutcome outcome = Objects.requireNonNull(perItem.execute(status -> {
                try {
                    authoring.setPrice(
                            tenantId, brandId, priceBookId, item.type(), item.priceableId(), item.amountMinor());
                    if (dryRun) {
                        // Every check setPrice runs has now run for real, against a
                        // real transaction; roll it back rather than re-deriving
                        // whether it would have succeeded.
                        status.setRollbackOnly();
                    }
                    return BulkPriceOutcome.applied(item.type(), item.priceableId(), previous, item.amountMinor());
                } catch (PriceAuthoringService.UnknownPriceableException
                        | PriceAuthoringService.PriceBookLifecycleException
                        | IllegalArgumentException failure) {
                    status.setRollbackOnly();
                    return BulkPriceOutcome.rejected(
                            item.type(), item.priceableId(), previous, item.amountMinor(), problemCodeFor(failure));
                }
            }));
            outcomes.add(outcome);
        }

        long applied = outcomes.stream().filter(BulkPriceOutcome::applied).count();
        return new BulkPriceChangeReport(items.size(), (int) applied, items.size() - (int) applied, dryRun, outcomes);
    }

    private static Map<PriceableType, Set<UUID>> idsByType(List<BulkPriceItem> items) {
        return items.stream()
                .collect(Collectors.groupingBy(
                        BulkPriceItem::type, Collectors.mapping(BulkPriceItem::priceableId, Collectors.toSet())));
    }

    private static String problemCodeFor(RuntimeException failure) {
        if (failure instanceof PriceAuthoringService.UnknownPriceableException) {
            return "UNKNOWN_PRICEABLE";
        }
        if (failure instanceof PriceAuthoringService.PriceBookLifecycleException) {
            return "PRICE_BOOK_NOT_AUTHORABLE";
        }
        return "INVALID_AMOUNT";
    }

    /** One price to set, the same shape {@link PriceAuthoringService#setPrice} already takes. */
    public record BulkPriceItem(PriceableType type, UUID priceableId, long amountMinor) {}

    /**
     * One item's outcome. {@code previousAmountMinor} is null only when the
     * book had no price at all for this priceable yet — a brand-new listing
     * being priced for the first time, not a fetch that failed.
     */
    public record BulkPriceOutcome(
            PriceableType type,
            UUID priceableId,
            boolean applied,
            @Nullable Long previousAmountMinor,
            long amountMinor,
            @Nullable String problemCode) {

        static BulkPriceOutcome applied(PriceableType type, UUID priceableId, @Nullable Long previous, long amount) {
            return new BulkPriceOutcome(type, priceableId, true, previous, amount, null);
        }

        static BulkPriceOutcome rejected(
                PriceableType type, UUID priceableId, @Nullable Long previous, long amount, String problemCode) {
            return new BulkPriceOutcome(type, priceableId, false, previous, amount, problemCode);
        }
    }

    /** The whole batch's outcome. {@code dryRun} true means every item above was rolled back regardless of its own outcome. */
    public record BulkPriceChangeReport(
            int totalItems, int appliedCount, int failedCount, boolean dryRun, List<BulkPriceOutcome> items) {}
}
