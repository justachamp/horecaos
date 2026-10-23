package uz.horecaos.platform.fiscal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fiscal.api.FiscalTerminalDirectory;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.fiscal.domain.BusinessZone;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;
import uz.horecaos.platform.fiscal.domain.FiscalReasonCode;
import uz.horecaos.platform.fiscal.domain.FiscalReportingPolicy;
import uz.horecaos.platform.fiscal.domain.ReportingDeadline;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.NewFiscalDocument;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.OrderOwingADocument;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.SubmissionCandidate;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;

/**
 * The half of the fiscal lifecycle that is about the <em>order</em> (ADR 0038).
 *
 * <p>{@link FiscalDocumentService} owns what the passage of time means once a
 * provider has been asked. This owns the two steps before that, which ADR 0038
 * calls the remaining half of rollout stage 4: an order that finished acquires
 * the obligation it owes, and an obligation whose payment has captured is sent.
 *
 * <p><strong>Two failures this exists to make impossible, and how.</strong>
 *
 * <ul>
 *   <li><em>A receipt issued twice for one order.</em> Three things stack, and
 *   none of them relies on the other two. The candidate query returns only orders
 *   with no sale document at all. The insert is {@code ON CONFLICT DO NOTHING}
 *   against V0039's partial unique index, which is {@code NULLS NOT DISTINCT} and
 *   therefore covers the single-tender order that carries a null
 *   {@code tender_id} — the common case, and the one a default index would have
 *   silently exempted. And the submission claims the row with a conditional
 *   {@code PENDING -> SUBMITTED} update <em>before</em> the provider is asked, so
 *   two nodes holding the same row send once between them. Two sale receipts for
 *   one payment cannot be withdrawn from a tax authority, only corrected, so the
 *   safe direction is to send nothing when in doubt.</li>
 *   <li><em>A receipt issued under the wrong company.</em> The seller is resolved
 *   from the branch and the order's own business date — never from an entity
 *   identifier somebody passed in — and the database refuses two assignments
 *   covering one day, so the resolver can never pick between two correct answers
 *   by row order. Where nothing resolves, the document is opened
 *   {@code BLOCKED} rather than opened with a null or a tenant default: an
 *   unissued receipt is a problem an operator can fix, and a receipt naming the
 *   wrong taxpayer is one nobody notices. The entity is then snapshotted onto the
 *   document, and the port contract requires the provider request to be built from
 *   that snapshot rather than from a second resolution.</li>
 * </ul>
 *
 * <p>Nothing here calls a provider. The external call is the sweeper's, made
 * outside any transaction, because the pool is ten connections wide and shared by
 * every module — see {@code ExternalCallTransactionBoundaryTests}. The methods
 * below are therefore short transactions on either side of that call rather than
 * one transaction around it.
 */
@Service
public class FiscalObligationService {

    private static final Logger log = LoggerFactory.getLogger(FiscalObligationService.class);

    /** What a document that cannot be sent as it stands says; also the retry's sentence for the same outcome. */
    static final String NOT_SENDABLE_NOTE =
            "the receipt cannot be sent as it stands (no seller snapshot, or goods the issuing path cannot carry); nothing was sent";

    private final JdbcFiscalLifecycleStore documents;
    private final LegalEntityDirectory sellers;
    private final FiscalReportingPolicyService policies;
    private final FiscalTerminalDirectory terminals;
    private final Clock clock;
    private final ZoneId fallbackZone;
    private final Duration lookback;

    @Autowired
    public FiscalObligationService(
            JdbcFiscalLifecycleStore documents,
            LegalEntityDirectory sellers,
            FiscalReportingPolicyService policies,
            FiscalTerminalDirectory terminals,
            Clock clock,
            @Value("${horecaos.fiscal.business-timezone:Asia/Tashkent}") String fallbackZone,
            @Value("${horecaos.fiscal.obligation-opener.lookback:P7D}") Duration lookback) {
        this.documents = documents;
        this.sellers = sellers;
        this.policies = policies;
        this.terminals = terminals;
        this.clock = clock;
        this.fallbackZone = ZoneId.of(fallbackZone);
        this.lookback = lookback;
    }

    /** With no terminals anywhere: every cash sale is the tenant's unevidenced obligation, as before ADR 0166. */
    public FiscalObligationService(
            JdbcFiscalLifecycleStore documents,
            LegalEntityDirectory sellers,
            FiscalReportingPolicyService policies,
            Clock clock,
            String fallbackZone,
            Duration lookback) {
        this(documents, sellers, policies, (tenantId, locationId) -> false, clock, fallbackZone, lookback);
    }

    // ------------------------------------------------- opening the obligation

    /**
     * Gives every recently completed order the fiscal document it owes.
     *
     * <p>ADR 0038's exit criterion in one method: no accepted order reaches its end
     * with no fiscal status at all. Every order this touches leaves with a
     * document, including the ones that can only be blocked — an order nobody can
     * receipt is a piece of work, and the state that says so is the whole point of
     * {@code BLOCKED} carrying a reason.
     *
     * <p>Only {@code COMPLETED}. A rejected, expired or cancelled order is terminal
     * as well and none of them is a sale; opening obligations for those would fill
     * the worklist with abandoned checkouts, which is the same defect as sweeping
     * {@code PENDING}.
     *
     * <p>The whole batch is one transaction, like the reporting sweep and with the
     * same trade: a failure loses a batch rather than a document, and the next run
     * finds the same orders because nothing about them changed.
     *
     * @return how many documents this run opened
     */
    @Transactional
    public int openObligations(int batchSize) {
        Instant now = clock.instant();
        List<OrderOwingADocument> candidates =
                documents.ordersOwingADocument(now.minus(lookback), fallbackZone.getId(), batchSize);

        int opened = 0;
        for (OrderOwingADocument candidate : candidates) {
            if (documents.open(obligationFor(candidate, now))) {
                opened++;
            }
        }
        return opened;
    }

    /**
     * Decides what document a completed order should get, and under which company.
     *
     * <p>Every branch here ends in a row. There is deliberately no path that
     * returns nothing: an order that this method cannot classify is the exact
     * silence ADR 0038 exists to remove, and "we could not tell" is a reason code
     * rather than an omission.
     */
    private NewFiscalDocument obligationFor(OrderOwingADocument order, Instant now) {
        UUID id = UUID.randomUUID();

        // The date the seller is resolved on, and the date the document keeps (ADR 0038): the local date,
        // in the branch's calendar, on which the order closed. Taken once, here, so the date a document
        // was resolved on and the date it records cannot differ.
        LocalDate businessDate = LocalDate.ofInstant(
                order.closedAt(), BusinessZone.resolve(order.businessZone(), fallbackZone, "Order " + order.orderId()));

        if (order.paymentIntentId() == null) {
            // No payment record at all. Reachable: createIntent answers null for a
            // method this build does not implement, and the order still completed.
            return blocked(
                    id,
                    order,
                    null,
                    now,
                    businessDate,
                    "the order completed with no payment record, so no provider and no "
                            + "terminal can be identified");
        }

        boolean terminalReceipt = order.cash() && terminals.hasCapableTerminal(order.tenantId(), order.locationId());
        if (order.cash() && !terminalReceipt) {
            // The cash NOT_APPLICABLE row is written by the payments seam in the
            // same transaction as the intent, so reaching here means that row is
            // missing rather than that this is an ordinary cash order. ADR 0038 is
            // explicit about which way to record it: a cash order with no path is
            // BLOCKED with NO_FISCAL_PATH, never NOT_APPLICABLE. Writing the
            // decision record here instead would be this module inventing the
            // 2026-08-22 decision on payments' behalf for a row whose absence is
            // itself the anomaly worth seeing.
            //
            // A cash sale at a location with a capable terminal (ADR 0166 «Providers») is the other
            // reason that row is absent: payments wrote none, because the receipt is owed by the
            // tenant's own equipment and this module opens it, below, for the sweeper to send to it.
            return blocked(
                    id,
                    order,
                    null,
                    now,
                    businessDate,
                    "a cash leg with no recorded fiscal decision; the receipt is owed by the "
                            + "restaurant's own equipment and no terminal is bound");
        }

        Optional<FiscalSeller> seller = sellers.sellerFor(order.tenantId(), order.locationId(), businessDate);

        if (seller.isEmpty()) {
            return blocked(
                    id,
                    order,
                    null,
                    now,
                    businessDate,
                    sellers.isWired()
                            ? "no legal entity is assigned to this location on " + businessDate
                            : "tenant.legal_entities is not present in this deployment (ADR 0038 "
                                    + "rollout stage 1), so no seller can be resolved");
        }
        if (!seller.get().active()) {
            // Suspended or archived. Blocked rather than silently falling through to
            // another company: which entity sells at a branch is the tenant's
            // decision and not one the platform may make on its behalf.
            return blocked(
                    id,
                    order,
                    seller.get().legalEntityId(),
                    now,
                    businessDate,
                    "legal entity %s is not active and cannot be named as the seller"
                            .formatted(seller.get().code()));
        }

        return new NewFiscalDocument(
                id,
                order.tenantId(),
                order.orderId(),
                seller.get().legalEntityId(),
                order.paymentIntentId(),
                order.providerType(),
                FiscalDocumentState.PENDING,
                terminalReceipt ? FiscalReasonCode.AWAITING_TERMINAL : FiscalReasonCode.AWAITING_CAPTURE,
                terminalReceipt
                        ? "cash collected; awaiting the tenant's fiscal terminal"
                        : order.captured()
                                ? "captured; awaiting submission to " + order.providerType()
                                : "awaiting capture before " + order.providerType() + " can be asked",
                now,
                order.tenderId(),
                businessDate);
    }

    private NewFiscalDocument blocked(
            UUID id,
            OrderOwingADocument order,
            @Nullable UUID legalEntityId,
            Instant now,
            LocalDate businessDate,
            String why) {
        // At WARN with the identifiers and no evidence on it, because ADR 0029 keeps
        // fiscal signs and receipt URLs out of logs and this line has to be
        // greppable when somebody asks why an order has no receipt.
        log.warn("Order {} completed and cannot be fiscalized: {}", order.orderId(), why);
        return new NewFiscalDocument(
                id,
                order.tenantId(),
                order.orderId(),
                legalEntityId,
                order.paymentIntentId(),
                order.providerType(),
                FiscalDocumentState.BLOCKED,
                FiscalReasonCode.NO_FISCAL_PATH,
                truncated(why),
                now,
                order.tenderId(),
                businessDate);
    }

    // ---------------------------------------------------------- submitting it

    /**
     * Claims every document that is ready to be sent, and moves each to
     * {@code SUBMITTED} before anything leaves the building.
     *
     * <p>Claim first, send second. Two nodes reading the same {@code PENDING} row
     * both try the conditional update and exactly one matches, so exactly one
     * reaches Click; asking first and recording afterwards would let both, which is
     * how one payment acquires two sale receipts.
     *
     * <p>Its own transaction, and a short one: the caller makes the provider call
     * outside it. A connection held across a call to something this platform does
     * not control is one of ten, shared by every module.
     *
     * @return the documents this node now owns and must settle
     */
    @Transactional
    public List<ClaimedSubmission> claimSubmissions(int batchSize) {
        Instant now = clock.instant();
        List<SubmissionCandidate> candidates = documents.claimSubmittableDocuments(fallbackZone.getId(), batchSize);

        Map<UUID, FiscalReportingPolicy> byTenant = new HashMap<>();
        List<ClaimedSubmission> claimed = new ArrayList<>();

        for (SubmissionCandidate candidate : candidates) {
            if (candidate.legalEntityId() == null) {
                // The one refusal that is not about the provider. Neither Click nor
                // Payme takes a seller identity as a request field — the service and
                // the cashbox are the taxpayer — so submitting without a resolved
                // entity means the receipt is issued under whichever merchant account
                // the resolution happens to find. Blocked, visibly, and never sent.
                documents.blockUnsent(
                        candidate.tenantId(),
                        candidate.id(),
                        FiscalReasonCode.NO_FISCAL_PATH,
                        "no legal entity is recorded on this document, so a receipt could not " + "name its seller",
                        now);
                log.warn(
                        "Fiscal document {} for order {} is BLOCKED: it names no legal entity.",
                        candidate.id(),
                        candidate.orderId());
                continue;
            }

            FiscalReportingPolicy policy = byTenant.computeIfAbsent(candidate.tenantId(), policies::forTenant);

            // The deadline the document will be judged against, written as it is
            // claimed. ADR 0038 asks for exactly this — a blocked document whose
            // deadline was derived after the fact can tell an operator that it is
            // late and not by how much or against what.
            Instant deadline = ReportingDeadline.of(
                            now,
                            null,
                            policy.deadlineFor(candidate.providerType()),
                            BusinessZone.resolve(candidate.businessZone(), fallbackZone, "Document " + candidate.id()))
                    .effective();

            if (!documents.claimForSubmission(
                    candidate.tenantId(), candidate.id(), candidate.version(), deadline, now)) {
                // Somebody else won the row between the select and the update. They
                // will send it; this node must not.
                continue;
            }

            claimed.add(new ClaimedSubmission(
                    candidate.tenantId(),
                    candidate.id(),
                    candidate.orderId(),
                    candidate.legalEntityId(),
                    candidate.providerType(),
                    candidate.version() + 1));
        }
        return claimed;
    }

    /**
     * Records what the provider said about a claimed document.
     *
     * <p>Only three outcomes write anything here (none of them a provider's answer), and the reason the other four do not
     * is the same in each case: the payments seam has already written what it
     * learned. It holds the evidence — the fiscal sign, the receipt identifiers,
     * the provider's status code — and a second writer for those would be a second
     * authority over what the tax authority was told.
     *
     * <p>{@code UNCERTAIN} in particular is left exactly where it is, in
     * {@code SUBMITTED}. A non-answer is not a failure: the request may have
     * arrived, and recording it as failed would invite a resubmission, which is the
     * one action that could create a second document with a tax authority. The
     * reporting sweeper is what settles it.
     */
    @Transactional
    public void settle(ClaimedSubmission claim, PartnerFiscalizationPort.Outcome outcome) {
        Instant now = clock.instant();
        switch (outcome) {
            case NOT_WIRED -> {
                // Nothing was sent, so the claim describes a request that does not
                // exist. Left SUBMITTED it would be blocked an hour later as
                // "the provider did not report", about a provider nobody asked.
                documents.releaseUnsentClaim(
                        claim.tenantId(),
                        claim.documentId(),
                        "no partner fiscalization is wired; nothing was sent",
                        now);
                log.warn(
                        "Fiscal document {} for order {} was not sent: {}.",
                        claim.documentId(),
                        claim.orderId(),
                        PartnerFiscalizationPort.NOT_WIRED_WARNING);
            }
            case NO_PROVIDER_PATH -> {
                documents.blockUnsent(
                        claim.tenantId(),
                        claim.documentId(),
                        FiscalReasonCode.NO_FISCAL_PATH,
                        "legal entity holds no active %s merchant account".formatted(claim.providerType()),
                        now);
                log.warn(
                        "Fiscal document {} for order {} is BLOCKED: legal entity {} has no "
                                + "active {} merchant account.",
                        claim.documentId(),
                        claim.orderId(),
                        claim.legalEntityId(),
                        claim.providerType());
            }
            case NO_TERMINAL -> {
                documents.blockUnsent(
                        claim.tenantId(),
                        claim.documentId(),
                        FiscalReasonCode.NO_FISCAL_PATH,
                        "no active fiscal terminal of the selling legal entity is bound to this location",
                        now);
                log.warn(
                        "Fiscal document {} for order {} is BLOCKED: no capable terminal of legal entity {} at the location.",
                        claim.documentId(),
                        claim.orderId(),
                        claim.legalEntityId());
            }
            case TERMINAL_UNREACHABLE -> {
                // Nothing left the building, so the claim describes a request that does not exist; the
                // document is blocked for the one reason that names a device, and an operator (or the
                // retry button once the terminal is back) sends it. Releasing it to be tried again every
                // minute would hide a terminal that has been down since morning.
                documents.blockUnsent(
                        claim.tenantId(),
                        claim.documentId(),
                        FiscalReasonCode.TERMINAL_OFFLINE,
                        "the tenant's fiscal terminal could not be reached; nothing was issued",
                        now);
                log.warn(
                        "Fiscal document {} for order {} is BLOCKED: the fiscal terminal could not be reached.",
                        claim.documentId(),
                        claim.orderId());
            }
            case BLOCKED_CLASSIFICATION ->
                blockForLines(
                        claim,
                        FiscalReasonCode.CLASSIFICATION_MISSING,
                        "a priced item, option or the delivery fee of the order has no complete fiscal "
                                + "classification; nothing was sent",
                        now);
            case BLOCKED_TAX ->
                blockForLines(
                        claim,
                        FiscalReasonCode.VAT_NOT_EXPRESSIBLE,
                        "the tax rule of the sale cannot be stated on a receipt; nothing was sent",
                        now);
            case BLOCKED_RECONCILIATION ->
                blockForLines(
                        claim,
                        FiscalReasonCode.RECEIPT_DOES_NOT_RECONCILE,
                        "the receipt lines cannot be made to add up to the payment; nothing was sent",
                        now);
            case BLOCKED_MARKS -> {
                // ADR 0161: nothing was built and nothing was sent, so the claim describes a request that
                // does not exist; the document is blocked for the one reason that names the obstacle, and
                // is reopened when the marks complete. CLASSIFICATION_MISSING would say a priceable node has
                // no ИКПУ, which is a different fact.
                documents.blockUnsent(
                        claim.tenantId(),
                        claim.documentId(),
                        FiscalReasonCode.MARKS_INCOMPLETE,
                        "a marked line of the order holds fewer scanned units than it has; nothing was sent",
                        now);
                log.warn(
                        "Fiscal document {} for order {} is BLOCKED: a marked line is short of scanned units.",
                        claim.documentId(),
                        claim.orderId());
            }
            case NOT_SENDABLE -> {
                // Nothing was built and nothing was sent, so the claim describes a request that does not exist. Left
                // SUBMITTED it would be blocked an hour later as «the provider did not report».
                documents.blockUnsent(
                        claim.tenantId(), claim.documentId(), FiscalReasonCode.NO_FISCAL_PATH, NOT_SENDABLE_NOTE, now);
                log.warn(
                        "Fiscal document {} for order {} is BLOCKED: it cannot be sent as it stands.",
                        claim.documentId(),
                        claim.orderId());
            }
            case ISSUED, ALREADY_ISSUED, REJECTED, UNCERTAIN -> {
                // Payments wrote the outcome and its evidence as the provider
                // answered. Nothing to add, and nothing this module is entitled to
                // overwrite.
            }
        }
    }

    /**
     * Nothing was built and nothing was sent, so the claim describes a request that does not exist; the
     * document is blocked for the one reason that names the obstacle (ADR 0166, Decision 6). Left
     * {@code SUBMITTED} it would be blocked an hour later as «the provider did not report» about a provider
     * nobody asked.
     */
    private void blockForLines(ClaimedSubmission claim, String reasonCode, String note, Instant now) {
        documents.blockUnsent(claim.tenantId(), claim.documentId(), reasonCode, note, now);
        log.warn("Fiscal document {} for order {} is BLOCKED: {}.", claim.documentId(), claim.orderId(), reasonCode);
    }

    /** A document this node has claimed and is about to send. */
    public record ClaimedSubmission(
            UUID tenantId, UUID documentId, UUID orderId, UUID legalEntityId, String providerType, int version) {

        /**
         * Stable for this document and this claim, so a sweep that is somehow run
         * twice over one claim asks the provider once.
         */
        public String idempotencyKey() {
            return "fiscal-submit:%s:%d".formatted(documentId, version);
        }
    }

    /** {@code reason_note} is varchar(255), and a truncated reason beats a failed insert. */
    private static String truncated(String note) {
        return note.length() <= 255 ? note : note.substring(0, 252) + "...";
    }
}
