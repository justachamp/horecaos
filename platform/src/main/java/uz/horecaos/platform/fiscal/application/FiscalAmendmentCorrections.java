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
 * table is ordering's and append-only), so the pass re-reads the flagged revisions of a window and every
 * decision is idempotent: a revision already answered by a request on a document is skipped by the request
 * itself ({@code source_revision}, V0686).
 */
@Service
public class FiscalAmendmentCorrections {

    private static final Logger log = LoggerFactory.getLogger(FiscalAmendmentCorrections.class);

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
     * One pass.
     *
     * @return how many correction requests this pass opened
     */
    public int openRequests(int batchSize) {
        List<FlaggedRevision> flagged = feed.flaggedSince(clock.instant().minus(lookback), batchSize);

        Map<OrderKey, FlaggedRevision> latest = new LinkedHashMap<>();
        for (FlaggedRevision revision : flagged) {
            latest.merge(
                    new OrderKey(revision.tenantId(), revision.orderId()),
                    revision,
                    (earlier, later) -> later.revision() >= earlier.revision() ? later : earlier);
        }

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
