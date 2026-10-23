package uz.horecaos.platform.fiscal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.FiscalDocumentRow;
import uz.horecaos.platform.ordering.api.FiscalCorrectionRevisionFeed;
import uz.horecaos.platform.ordering.api.FiscalCorrectionRevisionFeed.FlaggedRevision;

/**
 * The reader of {@code order_revisions.fiscal_correction_required} (ADR 0166 Decision 8, ADR 0039).
 *
 * <p>ADR 0039 §3.11: an amendment must never silently skip the consequence it declares just because the
 * module behind it has not shipped. Every financial amendment has written the flag since V0395; this is where
 * it is read. When an order is amended <em>after its receipt left</em>, the receipt no longer states the sale,
 * and the platform opens a correction request for it ({@link FiscalCorrectionService#openForAmendment}).
 *
 * <p><strong>Which revisions ask.</strong> Only one for which an {@code ISSUED} sale of the order was sent
 * before the revision was appended. A receipt not yet sent will be built from the amended order, a receipt
 * still awaiting its provider will become issued and be found by a later pass (the revision stays inside the
 * lookback), and one already {@code CORRECTED}, {@code VOIDED} or being corrected has an answer in flight. Of
 * several flagged revisions of one order, the latest asks, and it covers the earlier ones.
 *
 * <p><strong>Polling, with the lookback as the only state.</strong> The flag cannot be cleared by fiscal (the
 * table is ordering's and append-only), so the pass re-reads the flagged revisions of a window, all of them and
 * not the oldest few, and every decision is idempotent: a revision already answered by a request on a document
 * is skipped by the request itself ({@code source_revision}, V0686).
 */
@Service
public class FiscalAmendmentCorrections {

    private static final Logger log = LoggerFactory.getLogger(FiscalAmendmentCorrections.class);

    /** A ceiling on one pass's reading, so a feed that misbehaves cannot hold the sweeper for ever. */
    private static final int MAX_PAGES = 10_000;

    private final FiscalCorrectionRevisionFeed feed;
    private final JdbcFiscalLifecycleStore documents;
    private final FiscalCorrectionService corrections;
    private final Clock clock;
    private final Duration lookback;

    public FiscalAmendmentCorrections(
            FiscalCorrectionRevisionFeed feed,
            JdbcFiscalLifecycleStore documents,
            FiscalCorrectionService corrections,
            Clock clock,
            @Value("${horecaos.fiscal.amendment-corrections.lookback:P30D}") Duration lookback) {
        this.feed = feed;
        this.documents = documents;
        this.corrections = corrections;
        this.clock = clock;
        this.lookback = lookback;
    }

    /**
     * One pass, over the whole lookback window.
     *
     * <p><strong>The window is drained, a page at a time.</strong> The flag is never cleared, so every pass reads
     * the same old revisions again; a pass that took only the oldest {@code pageSize} of them looked at the first few
     * days of the window and stopped, and an order amended after that was not asked about until the older ones aged
     * out, weeks later and after the tenant's tax filing. The pages are read to the first one that is not full, the
     * latest revision of each order is kept (it speaks for the earlier ones, wherever a page boundary fell), and only
     * then are the documents looked at. The cost is the window's flagged orders, one read of their documents each,
     * every interval.
     *
     * @param pageSize how many flagged revisions the feed is asked for at a time; it bounds a read, not the pass
     * @return how many correction requests this pass opened
     */
    public int openRequests(int pageSize) {
        Map<OrderKey, FlaggedRevision> latest = latestFlaggedRevisions(Math.max(1, pageSize));

        int opened = 0;
        for (FlaggedRevision revision : latest.values()) {
            try {
                for (FiscalDocumentRow document : documents.forOrder(revision.tenantId(), revision.orderId())) {
                    if (owesACorrection(document, revision)
                            && corrections.openForAmendment(revision.tenantId(), document.id(), revision.revision())) {
                        opened++;
                    }
                }
            } catch (RuntimeException failure) {
                // One order's failure does not stop the pass; it is found again next time. Identifiers only.
                log.error(
                        "A correction request for the amendment of order {} (revision {}) could not be opened",
                        revision.orderId(),
                        revision.revision(),
                        failure);
            }
        }
        return opened;
    }

    /** The latest flagged revision of every order in the window, read page by page to the end of it. */
    private Map<OrderKey, FlaggedRevision> latestFlaggedRevisions(int pageSize) {
        Instant since = clock.instant().minus(lookback);
        Map<OrderKey, FlaggedRevision> latest = new LinkedHashMap<>();
        FlaggedRevision after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<FlaggedRevision> flagged = feed.flaggedSince(since, after, pageSize);
            for (FlaggedRevision revision : flagged) {
                latest.merge(
                        new OrderKey(revision.tenantId(), revision.orderId()),
                        revision,
                        (earlier, later) -> later.revision() >= earlier.revision() ? later : earlier);
            }
            if (flagged.size() < pageSize) {
                return latest;
            }
            after = flagged.getLast();
        }
        // Not reachable with a window of weeks: it takes more than MAX_PAGES full pages. Loud, because what is cut
        // off is the newest amendments, and a pass that silently stopped here would be the defect this loop fixes.
        log.error(
                "The amendment window holds more than {} pages of flagged revisions; the newest were not read this pass",
                MAX_PAGES);
        return latest;
    }

    /** An issued sale whose receipt left before the revision was appended. */
    private static boolean owesACorrection(FiscalDocumentRow document, FlaggedRevision revision) {
        if (!"SALE".equals(document.documentType()) || document.state() != FiscalDocumentState.ISSUED) {
            return false;
        }
        Instant sent = sentAt(document);
        return sent != null && sent.isBefore(revision.createdAt());
    }

    /** When the receipt left: the submission, else the issue (a document recorded from the agent's own reply). */
    private static @Nullable Instant sentAt(FiscalDocumentRow document) {
        return document.submittedAt() != null ? document.submittedAt() : document.issuedAt();
    }

    private record OrderKey(UUID tenantId, UUID orderId) {}
}
