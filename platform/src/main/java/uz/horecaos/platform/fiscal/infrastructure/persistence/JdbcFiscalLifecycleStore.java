package uz.horecaos.platform.fiscal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;

/**
 * The fiscal document lifecycle, in SQL (ADR 0038).
 *
 * <p>Reads and writes {@code fiscal.fiscal_documents}, which V0039 moved here
 * from {@code payments} and left a compatibility view behind for. The payments
 * module still inserts through that view — the cash {@code NOT_APPLICABLE} row at
 * checkout, and the Payme {@code SetFiscalData} callback — and this store writes
 * only the columns that record the obligation and its progress, never the
 * evidence columns. The division is deliberate: payments records what a provider
 * said, and this module records what the order owes and what the passage of time
 * means.
 *
 * <p>Three of the queries here read outside the {@code fiscal} schema —
 * {@code ordering.orders}, {@code payments.payment_intents} and
 * {@code tenant.locations}. That is a SQL join and not a module dependency, and
 * it is what lets the obligation opener see an order that completed without one.
 * The alternative is an event from ordering that does not exist: no
 * {@code OrderCompleted} contract is published, and the thing being detected here
 * is again partly an <em>absence</em> — an order with no fiscal document at all.
 *
 * <p>Every read made on behalf of a person carries the tenant predicate. The one
 * that does not is {@link #claimReportingCandidates}, and its absence is the
 * point: the sweep is a platform job that belongs to no tenant, and a tenant
 * predicate on it would mean the sweep only covers the tenants somebody
 * remembered to enumerate.
 */
@Repository
public class JdbcFiscalLifecycleStore {

    private static final String DOCUMENT_COLUMNS = """
            id, tenant_id, order_id, legal_entity_id, tender_id, payment_intent_id,
            provider_type, document_type, corrects_document_id, status, reason_code,
            reason_note, external_receipt_id, fiscal_sign, submitted_at, issued_at,
            blocked_at, reporting_deadline_at, attempt_count, version, created_at
            """;

    private final JdbcClient jdbc;

    public JdbcFiscalLifecycleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims a batch of documents that have been waiting on a provider.
     *
     * <p>{@code FOR UPDATE OF d SKIP LOCKED}, so several nodes share the sweep
     * rather than each blocking every document twice, and so a node that dies
     * mid-batch releases its claim on rollback rather than stranding it.
     *
     * <p>The timezone comes from the branch, joined through the payment intent,
     * because that is where the fiscal document's location lives today: ADR 0038
     * snapshots a business date onto the order at acceptance and this build does
     * not, so the branch's own calendar day is the closest honest answer. It is a
     * {@code LEFT JOIN} with a fallback rather than an inner one — a document with
     * no intent must still be swept, and losing it to a join is exactly the
     * silence this sweep exists to end.
     *
     * @param submittedBefore a coarse floor. Nothing submitted more recently than
     *                        this is even considered, so that the per-tenant policy
     *                        is applied in Java to a small set rather than guessed
     *                        at in SQL
     */
    public List<ReportingCandidate> claimReportingCandidates(Instant submittedBefore,
            String fallbackZone, int limit) {
        return jdbc.sql("""
                SELECT d.id, d.tenant_id, d.order_id, d.provider_type, d.submitted_at,
                       d.reporting_deadline_at, d.version,
                       COALESCE(l.timezone, :fallbackZone) AS business_zone
                FROM fiscal.fiscal_documents d
                LEFT JOIN payments.payment_intents i
                       ON i.tenant_id = d.tenant_id AND i.id = d.payment_intent_id
                LEFT JOIN tenant.locations l
                       ON l.tenant_id = d.tenant_id AND l.id = i.location_id
                WHERE d.status = 'SUBMITTED'
                  AND d.submitted_at IS NOT NULL
                  AND d.submitted_at < :submittedBefore
                ORDER BY d.submitted_at
                LIMIT :limit
                FOR UPDATE OF d SKIP LOCKED
                """)
                .param("submittedBefore", utc(submittedBefore))
                .param("fallbackZone", fallbackZone)
                .param("limit", limit)
                .query((row, number) -> new ReportingCandidate(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getString("provider_type"),
                        instant(row, "submitted_at"),
                        instant(row, "reporting_deadline_at"),
                        row.getString("business_zone"),
                        row.getObject("version", Integer.class)))
                .list();
    }

    /**
     * Ages one document into {@code BLOCKED}.
     *
     * <p>Conditional on the document still being {@code SUBMITTED}, which is what
     * makes the sweep re-entrant and what makes a callback that arrives during the
     * sweep win. A late {@code SetFiscalData} landing between the claim and this
     * update leaves the row {@code ISSUED}, the update matches nothing, and the
     * correct outcome is the one that survives — the alternative would overwrite a
     * fiscal sign that is on file with the tax authority with the word "blocked".
     *
     * <p>The deadline that was actually applied is written onto the row as it
     * blocks. Without it, an operator looking at the worklist can see that a
     * document is overdue and not by how much or against what, and the false
     * positives this design accepts in writing would be unarguable rather than
     * merely tolerable.
     *
     * @return true when this call is the one that blocked it
     */
    public boolean block(UUID tenantId, UUID documentId, String reasonCode, String reasonNote,
            Instant appliedDeadline, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", documentId);
        parameters.put("reasonCode", reasonCode);
        parameters.put("reasonNote", reasonNote);
        parameters.put("deadline", utc(appliedDeadline));
        parameters.put("now", utc(now));

        return jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                SET status = 'BLOCKED',
                    reason_code = :reasonCode,
                    reason_note = :reasonNote,
                    blocked_at = :now,
                    reporting_deadline_at = COALESCE(reporting_deadline_at, :deadline),
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'SUBMITTED'
                """)
                .params(parameters)
                .update() == 1;
    }

    /**
     * Puts a blocked document back in the queue once its blocking condition is
     * cleared.
     *
     * <p>ADR 0038's {@code BLOCKED -> PENDING} arrow, and the only transition out
     * of {@code BLOCKED} an operator makes by hand. {@code blocked_at} is not
     * cleared: how long a provider left a document unanswered is the evidence that
     * decides whether the deadline is set correctly, and a column that erases
     * itself on resolution is a column that can only ever say "not currently
     * blocked".
     *
     * @return true when the version matched and this call reopened it
     */
    public boolean reopen(UUID tenantId, UUID documentId, int expectedVersion, String reasonCode,
            String reasonNote, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", documentId);
        parameters.put("expectedVersion", expectedVersion);
        parameters.put("reasonCode", reasonCode);
        parameters.put("reasonNote", reasonNote);
        parameters.put("now", utc(now));

        return jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                SET status = 'PENDING',
                    reason_code = :reasonCode,
                    reason_note = :reasonNote,
                    reporting_deadline_at = NULL,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND status = 'BLOCKED' AND version = :expectedVersion
                """)
                .params(parameters)
                .update() == 1;
    }

    /**
     * Counts one operator-initiated ask against the document.
     *
     * <p>On the document rather than in a side table, because ADR 0038 is
     * emphatic that a retry reuses the document: a second row would be a second
     * sale receipt, and two of those for one payment can only be corrected with
     * the tax authority, never deleted.
     */
    public boolean recordRetryAttempt(UUID tenantId, UUID documentId, int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                SET attempt_count = attempt_count + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                  AND status <> 'ISSUED' AND status <> 'NOT_APPLICABLE'
                """)
                .param("tenantId", tenantId).param("id", documentId)
                .param("expectedVersion", expectedVersion).param("now", utc(now))
                .update() == 1;
    }

    // ------------------------------------------------- opening the obligation

    /**
     * Orders that finished and have no sale document to show for it.
     *
     * <p>{@code COMPLETED} and nothing else. A rejected, expired, cancelled or
     * payment-failed order is terminal too, and none of them is a sale — opening a
     * sale obligation for one would put an unreceiptable row in the worklist for
     * every abandoned checkout, and a worklist that is mostly noise is a worklist
     * nobody reads.
     *
     * <p><strong>No {@code FOR UPDATE}.</strong> Every other claim in this module
     * locks its rows, and this one deliberately does not: the rows are
     * {@code ordering.orders}, and taking row locks on another module's live table
     * from a background sweep is how a fiscal job comes to block an order
     * transition. The race is settled instead where the rule already lives — the
     * insert is {@code ON CONFLICT DO NOTHING} against
     * {@code uq_fiscal_document_sale_per_tender}, so two nodes opening the same
     * obligation produce one document and the loser learns it did.
     *
     * <p>The candidate set drains: every order this returns leaves with a document
     * of some status, including the ones that can only be blocked. An order that
     * kept reappearing would be the same defect as a sweep that never runs.
     *
     * @param closedSince a floor on {@code closed_at}. An order that completed
     *                    before the fiscal module existed is not this sweep's to
     *                    open, and without the floor every run scans the whole
     *                    order history to find nothing
     */
    public List<OrderOwingADocument> ordersOwingADocument(Instant closedSince, String fallbackZone,
            int limit) {
        return jdbc.sql("""
                SELECT o.id AS order_id, o.tenant_id, o.brand_id, o.location_id,
                       o.total_minor, o.currency, o.closed_at,
                       COALESCE(l.timezone, :fallbackZone) AS business_zone,
                       i.id AS payment_intent_id, i.tender, i.provider_type,
                       i.status AS intent_status
                FROM ordering.orders o
                LEFT JOIN payments.payment_intents i
                       ON i.tenant_id = o.tenant_id AND i.order_id = o.id
                LEFT JOIN tenant.locations l
                       ON l.tenant_id = o.tenant_id AND l.id = o.location_id
                WHERE o.status = 'COMPLETED'
                  AND o.closed_at IS NOT NULL
                  AND o.closed_at >= :closedSince
                  AND NOT EXISTS (
                      SELECT 1 FROM fiscal.fiscal_documents d
                      WHERE d.tenant_id = o.tenant_id AND d.order_id = o.id
                        AND d.document_type = 'SALE')
                ORDER BY o.closed_at
                LIMIT :limit
                """)
                .param("closedSince", utc(closedSince))
                .param("fallbackZone", fallbackZone)
                .param("limit", limit)
                .query((row, number) -> new OrderOwingADocument(
                        row.getObject("order_id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getLong("total_minor"),
                        row.getString("currency"),
                        instant(row, "closed_at"),
                        row.getString("business_zone"),
                        row.getObject("payment_intent_id", UUID.class),
                        row.getString("tender"),
                        row.getString("provider_type"),
                        row.getString("intent_status")))
                .list();
    }

    /**
     * Opens one obligation.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on the partial unique index V0039 built
     * with {@code NULLS NOT DISTINCT}, which is the sentence "one SALE per settled
     * tender" written where two nodes cannot argue with it.
     *
     * <p>The {@code WHERE document_type = 'SALE'} on the conflict target is not
     * decoration. The index is partial, and PostgreSQL will only infer a partial
     * index from an {@code ON CONFLICT} that repeats its predicate — without it
     * the statement fails outright with "no unique or exclusion constraint
     * matching the ON CONFLICT specification", which is at least a loud failure
     * rather than a quiet duplicate.
     *
     * @return true when this call is the one that opened it. False is not a
     *         failure: it means a document already exists for this leg, which is
     *         exactly the outcome asked for
     */
    public boolean open(NewFiscalDocument document) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", document.id());
        parameters.put("tenantId", document.tenantId());
        parameters.put("orderId", document.orderId());
        parameters.put("legalEntityId", document.legalEntityId());
        parameters.put("paymentIntentId", document.paymentIntentId());
        parameters.put("providerType", document.providerType());
        parameters.put("status", document.state().name());
        parameters.put("reasonCode", document.reasonCode());
        parameters.put("reasonNote", document.reasonNote());
        parameters.put("blockedAt", document.state() == FiscalDocumentState.BLOCKED
                ? utc(document.openedAt())
                : null);
        parameters.put("now", utc(document.openedAt()));

        return jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (
                    id, tenant_id, order_id, legal_entity_id, payment_intent_id, provider_type,
                    document_type, status, reason_code, reason_note, blocked_at, attempt_count,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :orderId, :legalEntityId, :paymentIntentId, :providerType,
                    'SALE', :status, :reasonCode, :reasonNote, :blockedAt, 0, 1, :now, :now)
                ON CONFLICT (tenant_id, order_id, tender_id)
                    WHERE document_type = 'SALE' DO NOTHING
                """)
                .params(parameters)
                .update() == 1;
    }

    // ---------------------------------------------------------- submitting it

    /**
     * Documents that are ready to be sent and have not been.
     *
     * <p>{@code PENDING} with a captured payment behind it. Both halves matter:
     * Click's {@code submit_items} needs a CLICK {@code payment_id} that does not
     * exist before capture, so submitting earlier cannot work, and a document
     * whose payment never captures is a customer who abandoned the checkout rather
     * than a receipt anybody owes.
     *
     * <p>A {@code PENDING} document with no legal entity is returned too, and is
     * not sent. The caller blocks it: a receipt has to name a seller, and a
     * document that cannot name one must become visible work rather than go out
     * under whichever merchant account the tenant happens to hold.
     */
    public List<SubmissionCandidate> claimSubmittableDocuments(String fallbackZone, int limit) {
        return jdbc.sql("""
                SELECT d.id, d.tenant_id, d.order_id, d.legal_entity_id, d.provider_type,
                       d.version, d.created_at,
                       COALESCE(l.timezone, :fallbackZone) AS business_zone
                FROM fiscal.fiscal_documents d
                JOIN payments.payment_intents i
                  ON i.tenant_id = d.tenant_id AND i.id = d.payment_intent_id
                LEFT JOIN tenant.locations l
                       ON l.tenant_id = d.tenant_id AND l.id = i.location_id
                WHERE d.status = 'PENDING'
                  AND d.document_type = 'SALE'
                  AND d.provider_type IS NOT NULL
                  AND i.status = 'PAID'
                ORDER BY d.created_at
                LIMIT :limit
                FOR UPDATE OF d SKIP LOCKED
                """)
                .param("fallbackZone", fallbackZone)
                .param("limit", limit)
                .query((row, number) -> new SubmissionCandidate(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("legal_entity_id", UUID.class),
                        row.getString("provider_type"),
                        instant(row, "created_at"),
                        row.getString("business_zone"),
                        row.getObject("version", Integer.class)))
                .list();
    }

    /**
     * Claims a document for submission, before the provider is asked.
     *
     * <p>The ordering is the whole of the concurrency story, and it is the same one
     * {@code FiscalDocumentService.retry} uses: claim, then send. Two nodes reading
     * the same {@code PENDING} row both attempt this update and exactly one matches,
     * so exactly one reaches Click. Asking first and recording afterwards would let
     * both send, which is how one payment acquires two sale receipts — a
     * discrepancy with the tax authority that can only be corrected, never
     * withdrawn.
     *
     * <p>{@code reporting_deadline_at} is written here rather than derived later,
     * which is what ADR 0038 asks for: every document entering the submitted state
     * carries the deadline it will be judged against, so an operator looking at a
     * blocked document can see by how much it was late and against what.
     */
    public boolean claimForSubmission(UUID tenantId, UUID documentId, int expectedVersion,
            Instant reportingDeadlineAt, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", documentId);
        parameters.put("expectedVersion", expectedVersion);
        parameters.put("deadline", utc(reportingDeadlineAt));
        parameters.put("now", utc(now));

        return jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                SET status = 'SUBMITTED',
                    reason_code = 'AWAITING_PROVIDER',
                    reason_note = 'sent to the provider, awaiting its report',
                    submitted_at = :now,
                    reporting_deadline_at = :deadline,
                    attempt_count = attempt_count + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND status = 'PENDING' AND version = :expectedVersion
                """)
                .params(parameters)
                .update() == 1;
    }

    /**
     * Returns a claimed document to {@code PENDING} because nothing was sent.
     *
     * <p>Used for exactly one outcome: no implementation of the partner port is
     * present, so the claim describes a request that was never made. Leaving it
     * {@code SUBMITTED} would hand it to the reporting sweeper, which would block
     * it with {@code PROVIDER_REPORT_OVERDUE} — "the provider did not answer" about
     * a provider nobody asked. That is a worklist full of documents whose stated
     * reason is false, which is worse than the gap it describes.
     *
     * <p>Never used for an uncertain provider answer. A request that may have
     * arrived is not a request that was not sent, and the two are the difference
     * between a document that is safe to resend and one that is not.
     */
    public boolean releaseUnsentClaim(UUID tenantId, UUID documentId, String reasonNote,
            Instant now) {
        return jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                SET status = 'PENDING',
                    reason_code = 'AWAITING_CAPTURE',
                    reason_note = :reasonNote,
                    submitted_at = NULL,
                    reporting_deadline_at = NULL,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'SUBMITTED'
                """)
                .param("tenantId", tenantId).param("id", documentId)
                .param("reasonNote", reasonNote).param("now", utc(now))
                .update() == 1;
    }

    /**
     * Blocks a document that has not been sent.
     *
     * <p>Separate from {@link #block}, which is the sweeper's and is conditional on
     * {@code SUBMITTED}. One method taking a from-status would read more tidily and
     * would also let a caller block an {@code ISSUED} document by passing the wrong
     * constant; these two transitions have different preconditions because they
     * mean different things, and the statement is where that should be legible.
     */
    public boolean blockUnsent(UUID tenantId, UUID documentId, String reasonCode,
            String reasonNote, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", documentId);
        parameters.put("reasonCode", reasonCode);
        parameters.put("reasonNote", reasonNote);
        parameters.put("now", utc(now));

        return jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                SET status = 'BLOCKED',
                    reason_code = :reasonCode,
                    reason_note = :reasonNote,
                    blocked_at = :now,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND status IN ('PENDING', 'SUBMITTED')
                """)
                .params(parameters)
                .update() == 1;
    }

    public Optional<FiscalDocumentRow> find(UUID tenantId, UUID documentId) {
        return jdbc.sql("SELECT " + DOCUMENT_COLUMNS
                        + " FROM fiscal.fiscal_documents WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", documentId)
                .query(JdbcFiscalLifecycleStore::mapDocument)
                .optional();
    }

    /**
     * The blocked worklist, longest-waiting first.
     *
     * <p>Ordered by {@code blocked_at} rather than by when the order was placed,
     * because the question an operator is answering is "what has been waiting the
     * longest for me", not "what happened first".
     */
    public List<FiscalDocumentRow> blocked(UUID tenantId, String reasonCode, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("reasonCode", reasonCode);
        parameters.put("limit", limit);

        // The reason filter is cast explicitly on both sides. An untyped null
        // parameter compared with IS NULL is a value PostgreSQL cannot infer a type
        // for, and the failure is a startup-time surprise on a query that reads
        // perfectly well.
        return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + """
                 FROM fiscal.fiscal_documents
                 WHERE tenant_id = :tenantId AND status = 'BLOCKED'
                   AND (CAST(:reasonCode AS varchar) IS NULL
                        OR reason_code = CAST(:reasonCode AS varchar))
                 ORDER BY blocked_at
                 LIMIT :limit
                """)
                .params(parameters)
                .query(JdbcFiscalLifecycleStore::mapDocument)
                .list();
    }

    /** Every document for an order, oldest first. Plural, and never anything else. */
    public List<FiscalDocumentRow> forOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + """
                 FROM fiscal.fiscal_documents
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                 ORDER BY created_at
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query(JdbcFiscalLifecycleStore::mapDocument)
                .list();
    }

    /**
     * The coverage counts, over sale documents in a window.
     *
     * <p>Refunds and corrections are excluded. They are evidence of a reversal
     * rather than of a sale, and counting them would let a busy refund day read as
     * improved receipt coverage.
     */
    public CoverageCounts coverage(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT
                  count(*) AS total,
                  count(*) FILTER (WHERE status = 'ISSUED') AS issued,
                  count(*) FILTER (WHERE status = 'NOT_APPLICABLE') AS not_applicable,
                  count(*) FILTER (WHERE status = 'NOT_APPLICABLE'
                                     AND reason_code = :cashReason) AS cash,
                  count(*) FILTER (WHERE status = 'BLOCKED') AS blocked,
                  count(*) FILTER (WHERE status = 'FAILED') AS failed,
                  count(*) FILTER (WHERE status IN ('PENDING', 'SUBMITTED')) AS awaiting
                FROM fiscal.fiscal_documents
                WHERE tenant_id = :tenantId
                  AND document_type = 'SALE'
                  AND created_at >= :from AND created_at < :to
                """)
                .param("tenantId", tenantId)
                .param("cashReason",
                        uz.horecaos.platform.fiscal.domain.FiscalReasonCode
                                .CASH_TENDER_NO_PROVIDER_FISCALIZATION)
                .param("from", utc(from)).param("to", utc(to))
                .query((row, number) -> new CoverageCounts(
                        row.getLong("total"),
                        row.getLong("issued"),
                        row.getLong("not_applicable"),
                        row.getLong("cash"),
                        row.getLong("blocked"),
                        row.getLong("failed"),
                        row.getLong("awaiting")))
                .single();
    }

    /**
     * A completed order with no sale document, and what is known about how it was
     * paid.
     *
     * @param paymentIntentId null when the order carries no payment record at all.
     *                        Not impossible: {@code createIntent} answers null for
     *                        a method this build does not implement, and an order
     *                        that completed with no payment row still owes a
     *                        receipt and has no path to one
     * @param tender          {@code CASH} or {@code PROVIDER}, and null with the
     *                        intent
     * @param intentStatus    {@code PAID} is the capture that Click's
     *                        {@code submit_items} needs and that Payme's report
     *                        follows
     */
    public record OrderOwingADocument(
            UUID orderId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            long totalMinor,
            String currency,
            Instant closedAt,
            String businessZone,
            UUID paymentIntentId,
            String tender,
            String providerType,
            String intentStatus) {

        public boolean cash() {
            return "CASH".equals(tender);
        }

        public boolean captured() {
            return "PAID".equals(intentStatus);
        }
    }

    /** What this module needs about a document before it may be sent. */
    public record SubmissionCandidate(
            UUID id,
            UUID tenantId,
            UUID orderId,
            UUID legalEntityId,
            String providerType,
            Instant createdAt,
            String businessZone,
            int version) {
    }

    /**
     * An obligation about to be opened.
     *
     * <p>Deliberately carries no evidence field. This module opens obligations and
     * never records receipts: the fiscal sign, the receipt identifiers and the
     * protected payload references are written by the payments seam when a
     * provider answers, and a second writer for them would be a second authority
     * over what the tax authority was told.
     */
    public record NewFiscalDocument(
            UUID id,
            UUID tenantId,
            UUID orderId,
            UUID legalEntityId,
            UUID paymentIntentId,
            String providerType,
            FiscalDocumentState state,
            String reasonCode,
            String reasonNote,
            Instant openedAt) {
    }

    /** A document the sweep has claimed, with everything its deadline needs. */
    public record ReportingCandidate(
            UUID id,
            UUID tenantId,
            UUID orderId,
            String providerType,
            Instant submittedAt,
            Instant reportingDeadlineAt,
            String businessZone,
            int version) {
    }

    /**
     * One fiscal document, as this module reads it.
     *
     * <p>No fiscal sign and no receipt URL: those are ADR 0029 evidence and belong
     * to the authorized read in payments, not to a worklist. What is here is
     * enough to decide what to do about the document and nothing more.
     */
    public record FiscalDocumentRow(
            UUID id,
            UUID tenantId,
            UUID orderId,
            UUID legalEntityId,
            UUID tenderId,
            UUID paymentIntentId,
            String providerType,
            String documentType,
            UUID correctsDocumentId,
            FiscalDocumentState state,
            String reasonCode,
            String reasonNote,
            boolean hasEvidence,
            Instant submittedAt,
            Instant issuedAt,
            Instant blockedAt,
            Instant reportingDeadlineAt,
            int attemptCount,
            int version,
            Instant createdAt) {

        /**
         * Who owes the receipt, derived rather than stored.
         *
         * <p>A document with no provider is a cash or terminal leg, whose receipt
         * comes from the restaurant's own fiscal-capable equipment. Deriving it
         * keeps one fact in one place; a stored copy would have no maintainer on
         * the rows the payments module inserts.
         */
        public String responsibility() {
            return providerType == null ? "TERMINAL" : "PARTNER";
        }
    }

    /**
     * What a window of sale documents looks like.
     *
     * <p>Deliberately not a single "coverage percent". Cash is this market's
     * majority tender and every cash order carries {@code NOT_APPLICABLE}, so any
     * single figure that folds cash in with issued receipts reports an unreceipted
     * majority as a healthy number.
     */
    public record CoverageCounts(
            long total,
            long issued,
            long notApplicable,
            long cash,
            long blocked,
            long failed,
            long awaiting) {
    }

    private static FiscalDocumentRow mapDocument(ResultSet row, int rowNumber) throws SQLException {
        return new FiscalDocumentRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getObject("tender_id", UUID.class),
                row.getObject("payment_intent_id", UUID.class),
                row.getString("provider_type"),
                row.getString("document_type"),
                row.getObject("corrects_document_id", UUID.class),
                FiscalDocumentState.require(row.getString("status")),
                row.getString("reason_code"),
                row.getString("reason_note"),
                row.getString("fiscal_sign") != null || row.getString("external_receipt_id") != null,
                instant(row, "submitted_at"),
                instant(row, "issued_at"),
                instant(row, "blocked_at"),
                instant(row, "reporting_deadline_at"),
                // getInt answers 0 for SQL NULL, which would be a lie on a nullable
                // column. This one is NOT NULL DEFAULT 0, and it is read through
                // getObject anyway so that making it nullable later cannot silently
                // turn every count into zero.
                attemptCount(row),
                row.getObject("version", Integer.class),
                instant(row, "created_at"));
    }

    private static int attemptCount(ResultSet row) throws SQLException {
        Integer value = row.getObject("attempt_count", Integer.class);
        return value == null ? 0 : value;
    }

    /**
     * Null-safe, and read as an {@code OffsetDateTime} rather than through
     * {@code getTimestamp}, which applies the JVM's default zone. On a module whose
     * timestamps decide whether a reporting deadline has passed, a conversion that
     * means something different on a developer's laptop is not an option.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
