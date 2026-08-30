package uz.horecaos.platform.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReason;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.FiscalSubmission;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;

/**
 * The partner fiscal seam (ADR 0013, ADR 0038).
 *
 * <p>Two paths, and they invert each other. Click fiscalizes strictly after
 * capture, because {@code submit_items} needs a CLICK {@code payment_id} that does
 * not exist before the payment does. Payme fiscalizes from a {@code detail} object
 * fixed before the customer pays and reports the outcome back afterwards through
 * {@code SetFiscalData}, which may never arrive. A captured payment with no
 * receipt is reachable on both, by different mechanisms, which is why
 * {@link FiscalStatus#SUBMITTED} is a state someone watches.
 *
 * <p>And a third path that is not a path: cash, which neither provider can
 * fiscalize at all.
 */
@Service
public class PaymentFiscalService {

    private static final Logger log = LoggerFactory.getLogger(PaymentFiscalService.class);

    private final JdbcFiscalDocumentStore documents;
    private final Map<PaymentProviderType, FiscalReceiptPort> receiptPorts;

    public PaymentFiscalService(JdbcFiscalDocumentStore documents, List<FiscalReceiptPort> ports) {
        this.documents = documents;
        this.receiptPorts = ports.stream()
                .collect(java.util.stream.Collectors.toMap(FiscalReceiptPort::providerType, port -> port));
    }

    /**
     * Records that a cash order will receive no provider fiscal receipt.
     *
     * <p>The user's decision of 2026-08-22, written as a row rather than as the
     * absence of one. Neither provider can produce a receipt for a cash order:
     * Click's {@code submit_items} needs a CLICK {@code payment_id} that does not
     * exist, and Payme's fiscal data attaches to a Payme receipt that does not
     * exist. Click's {@code received_cash} is not the answer either — it is a
     * tender split <em>inside</em> a CLICK payment, and reading it as a cash-order
     * path builds a system that appears to fiscalize cash and does not, which is
     * invisible until an inspection.
     *
     * <p>A null status would mean "unknown". This is known, and if the decision
     * reverses — a fiscal terminal under ADR 0038's {@code TERMINAL} responsibility,
     * or its {@code OPERATOR} path — the affected orders must be found by a query on
     * the reason code rather than by inspecting orders one at a time. Cash is this
     * market's majority tender, so that query returns most of the traffic and not a
     * handful of edge cases.
     */
    @Transactional
    public UUID recordCashNotApplicable(PaymentIntent intent, Instant now) {
        FiscalDocument document = FiscalDocument.notApplicableForCash(
                UUID.randomUUID(), intent.tenantId(), intent.orderId(), intent.legalEntityId(), intent.id(), now);
        documents.insert(document);
        return document.id();
    }

    /**
     * Opens the obligation for a provider-settled order.
     *
     * <p>{@code PENDING} with {@link FiscalReason#AWAITING_CAPTURE}, because on
     * Click there is nothing to submit until a payment exists, and on Payme the
     * lines went out with the checkout and the outcome is what is awaited.
     */
    @Transactional
    public UUID openPartnerObligation(PaymentIntent intent, Instant now) {
        FiscalDocument document = new FiscalDocument(
                UUID.randomUUID(),
                intent.tenantId(),
                intent.orderId(),
                intent.legalEntityId(),
                intent.id(),
                null,
                intent.providerType(),
                uz.horecaos.platform.payments.domain.FiscalDocumentType.SALE,
                null,
                FiscalStatus.PENDING,
                FiscalReason.AWAITING_CAPTURE,
                "awaiting capture before the provider can be asked",
                List.of(),
                null,
                1,
                now);
        documents.insert(document);
        return document.id();
    }

    /**
     * Asks the provider for a receipt and records whatever comes back.
     *
     * <p>An uncertain submission is not resubmitted. Whether Click's
     * {@code submit_items} is idempotent for a repeated {@code payment_id} is an
     * open question to Click, so the safe reading is that it may not be, and the
     * resolution is a read-back through {@code GET payment/ofd_data/...} rather
     * than a second submission that could produce a duplicate document with a tax
     * authority.
     */
    @Transactional
    public FiscalStatus submit(FiscalDocument document, ProviderBinding binding, Instant now) {
        FiscalReceiptPort port = receiptPorts.get(binding.providerType());
        if (port == null || !binding.supportsPartnerFiscalization()) {
            log.warn(
                    "No fiscal receipt port for {}; document {} stays {}.",
                    binding.providerType(),
                    document.id(),
                    document.status());
            return document.status();
        }

        FiscalSubmission submission = port.submit(document, binding);

        switch (submission.classification()) {
            case SUCCESS -> {
                if (submission.status() == FiscalStatus.ISSUED) {
                    documents.recordEvidence(
                            document.tenantId(),
                            document.id(),
                            FiscalStatus.ISSUED,
                            FiscalReason.PARTNER_FISCALIZED,
                            submission.evidence(),
                            null,
                            submission.submittedAt());
                } else {
                    documents.recordSubmission(
                            document.tenantId(),
                            document.id(),
                            FiscalStatus.SUBMITTED,
                            FiscalReason.AWAITING_PROVIDER,
                            null,
                            submission.submittedAt());
                }
            }
            case REJECTED ->
                documents.recordEvidence(
                        document.tenantId(),
                        document.id(),
                        FiscalStatus.FAILED,
                        FiscalReason.PROVIDER_REJECTED,
                        new FiscalDocument.FiscalEvidence(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                submission.providerStatusCode(),
                                submission.providerMessage()),
                        null,
                        now);
            // A non-answer leaves the document exactly where it was, in SUBMITTED,
            // and the read-back is what settles it. Recording it as FAILED would
            // invite a resubmission, which is the one action that could create a
            // second document with a tax authority.
            case RETRYABLE, UNCERTAIN ->
                documents.recordSubmission(
                        document.tenantId(),
                        document.id(),
                        FiscalStatus.SUBMITTED,
                        FiscalReason.AWAITING_PROVIDER,
                        null,
                        submission.submittedAt());
        }

        return submission.status();
    }

    /**
     * Attaches evidence that arrived inbound.
     *
     * <p>Payme's {@code SetFiscalData} path, and Click's read-back. Guarded against
     * overwriting a document already {@code ISSUED}: a fiscal sign that is on file
     * with the tax authority is not something a late duplicate may rewrite.
     */
    @Transactional
    public boolean attachEvidence(
            UUID tenantId,
            UUID documentId,
            FiscalDocument.FiscalEvidence evidence,
            String protectedResponseReference,
            Instant issuedAt) {
        return documents.recordEvidence(
                tenantId,
                documentId,
                FiscalStatus.ISSUED,
                FiscalReason.PARTNER_FISCALIZED,
                evidence,
                protectedResponseReference,
                issuedAt);
    }

    /** Every fiscal document for an order. Plural, deliberately: see the store. */
    public List<FiscalDocument> forOrder(UUID tenantId, UUID orderId) {
        return documents.listForOrder(tenantId, orderId);
    }

    /**
     * The orders that carry no provider receipt because cash cannot have one.
     *
     * <p>The query ADR 0013 requires to exist before the decision is relied on.
     */
    public List<FiscalDocument> unfiscalizedCashOrders(UUID tenantId, LocalDate from, LocalDate to, int limit) {
        return documents.listNotApplicable(
                tenantId,
                FiscalReason.CASH_TENDER_NO_PROVIDER_FISCALIZATION,
                from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to.atStartOfDay(ZoneOffset.UTC).toInstant(),
                limit);
    }

    public Optional<FiscalDocument> find(UUID tenantId, UUID documentId) {
        return documents.find(tenantId, documentId);
    }
}
