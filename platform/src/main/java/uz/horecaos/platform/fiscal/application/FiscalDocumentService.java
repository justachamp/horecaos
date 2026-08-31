package uz.horecaos.platform.fiscal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fiscal.api.FiscalDocumentBlocked;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.fiscal.domain.BusinessZone;
import uz.horecaos.platform.fiscal.domain.FiscalCoverage;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;
import uz.horecaos.platform.fiscal.domain.FiscalReasonCode;
import uz.horecaos.platform.fiscal.domain.FiscalReportingPolicy;
import uz.horecaos.platform.fiscal.domain.ReportingDeadline;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.CoverageCounts;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.FiscalDocumentRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.ReportingCandidate;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * The half of the fiscal lifecycle that is about time (ADR 0038).
 *
 * <p>Three things happen here and they are all the same thing seen from different
 * ends. The sweep decides that a provider has been silent for long enough to
 * count as absent. The worklist puts what it decided in front of a person. The
 * resolution commands are what that person can do about it.
 *
 * <p>Nothing here submits to a provider directly. Building a Click {@code Items}
 * array or a Payme {@code detail} object needs the payment attempt, the merchant
 * binding and the single named som-to-tiyin conversion, all of which live in
 * payments and none of which belongs in a second place — so a retry goes out
 * through {@link PartnerFiscalizationPort}, whose implementation is payments' to
 * supply.
 */
@Service
public class FiscalDocumentService {

    private static final Logger log = LoggerFactory.getLogger(FiscalDocumentService.class);

    private final JdbcFiscalLifecycleStore documents;
    private final FiscalReportingPolicyService policies;
    private final PartnerFiscalizationPort partner;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId fallbackZone;
    private final Duration minimumAge;

    public FiscalDocumentService(
            JdbcFiscalLifecycleStore documents,
            FiscalReportingPolicyService policies,
            PartnerFiscalizationPort partner,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${horecaos.fiscal.business-timezone:Asia/Tashkent}") String fallbackZone,
            @Value("${horecaos.fiscal.sweeper.minimum-age:PT1M}") Duration minimumAge) {
        this.documents = documents;
        this.policies = policies;
        this.partner = partner;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.fallbackZone = ZoneId.of(fallbackZone);
        this.minimumAge = minimumAge;
    }

    /**
     * Ages every overdue submitted document into {@code BLOCKED}.
     *
     * <p>Idempotent and re-entrant, as ADR 0038 requires: it changes a status and
     * a reason and nothing else, it claims its batch with {@code SKIP LOCKED} so
     * two nodes divide the work rather than repeating it, and every write is
     * conditional on the document still being {@code SUBMITTED}. A callback that
     * arrives mid-sweep therefore wins — the update matches nothing, and the
     * receipt is the outcome that survives.
     *
     * <p><strong>The sweeper marks a document as needing a human, not as
     * finished.</strong> A late {@code SetFiscalData} is still accepted after the
     * block: it is idempotent on ({@code params.id}, {@code type}) and payments'
     * evidence write is guarded only against overwriting an already-issued
     * document, so an arrival clears the block on its way to {@code ISSUED} — or
     * to {@code FAILED}, if its {@code status_code} is non-zero, because arrival
     * is not proof of a receipt.
     *
     * <p>The whole batch is one transaction. That is what makes the claim hold,
     * and it means a failure loses a batch rather than a document; the next sweep
     * finds the same rows, because nothing about them changed.
     *
     * @return how many documents this sweep blocked
     */
    @Transactional
    public int sweepOverdueReports(int batchSize) {
        Instant now = clock.instant();
        List<ReportingCandidate> candidates =
                documents.claimReportingCandidates(now.minus(minimumAge), fallbackZone.getId(), batchSize);

        Map<UUID, FiscalReportingPolicy> byTenant = new HashMap<>();
        int blocked = 0;

        for (ReportingCandidate candidate : candidates) {
            FiscalReportingPolicy policy = byTenant.computeIfAbsent(candidate.tenantId(), policies::forTenant);

            ReportingDeadline deadline = ReportingDeadline.of(
                    candidate.submittedAt(),
                    candidate.reportingDeadlineAt(),
                    policy.deadlineFor(candidate.providerType()),
                    zoneOf(candidate));

            if (!deadline.passedAt(now)) {
                continue;
            }

            String note = reasonNote(candidate, deadline);
            if (documents.block(
                    candidate.tenantId(),
                    candidate.id(),
                    FiscalReasonCode.PROVIDER_REPORT_OVERDUE,
                    note,
                    deadline.effective(),
                    now)) {
                blocked++;
                // At WARN with the identifiers on it, because this is the log line
                // that has to be greppable during a tax inspection. No fiscal sign,
                // no receipt URL and no marking code: ADR 0029 keeps evidence out of
                // logs, and a status is not evidence.
                log.warn(
                        "Fiscal document {} for order {} is BLOCKED: {} did not report by {}.",
                        candidate.id(),
                        candidate.orderId(),
                        candidate.providerType() == null ? "the provider" : candidate.providerType(),
                        deadline.effective());

                // ADR 0058's operations trigger: the worklist alert ADR 0038
                // itself asks for, one message per block. Skipped, not
                // failed, when the candidate's payment intent named no
                // brand/location to route on — see ReportingCandidate's own
                // Javadoc for when that happens; the document is still
                // blocked and still on the worklist either way.
                if (candidate.brandId() != null && candidate.locationId() != null) {
                    events.publishEvent(new FiscalDocumentBlocked(
                            UUID.randomUUID(),
                            candidate.tenantId(),
                            candidate.brandId(),
                            candidate.locationId(),
                            candidate.id(),
                            candidate.orderId(),
                            FiscalReasonCode.PROVIDER_REPORT_OVERDUE,
                            now));
                }
            }
        }

        return blocked;
    }

    /** The blocked worklist for a tenant, longest-waiting first. */
    public List<FiscalDocumentRow> blocked(UUID tenantId, @Nullable String reasonCode, int limit) {
        return documents.blocked(tenantId, reasonCode, limit);
    }

    /** Every fiscal document for one order. Plural, always: see ADR 0038. */
    public List<FiscalDocumentRow> forOrder(UUID tenantId, UUID orderId) {
        return documents.forOrder(tenantId, orderId);
    }

    public Optional<FiscalDocumentRow> find(UUID tenantId, UUID documentId) {
        return documents.find(tenantId, documentId);
    }

    /** Whether a retry can actually reach a provider in this deployment. */
    public boolean partnerFiscalizationWired() {
        return partner.isWired();
    }

    /**
     * Receipt coverage over a window, in the shape ADR 0038 insists on.
     *
     * <p>Never a single percentage. See {@link FiscalCoverage}.
     */
    public FiscalCoverage coverage(UUID tenantId, Instant from, Instant to) {
        CoverageCounts counts = documents.coverage(tenantId, from, to);
        return new FiscalCoverage(
                from,
                to,
                counts.total(),
                counts.issued(),
                counts.notApplicable(),
                counts.cash(),
                counts.blocked(),
                counts.failed(),
                counts.awaiting(),
                partner.isWired());
    }

    /**
     * Asks the partner for this document's receipt again.
     *
     * <p>Never creates a second document, and never sends without reading first on
     * the Click path: {@code GET payment/ofd_data/…} answers with a populated
     * {@code qrCodeURL} when the earlier submission worked, and Click does not
     * document {@code submit_items} as idempotent. Both properties belong to the
     * adapter behind the port, which is why the retry goes through it rather than
     * around it.
     */
    @Transactional
    public RetryResult retry(
            UUID tenantId,
            UUID documentId,
            int expectedVersion,
            String idempotencyKey,
            ActorRef actor,
            String reason,
            @Nullable String correlationId) {

        FiscalDocumentRow document =
                documents.find(tenantId, documentId).orElseThrow(() -> new UnknownDocumentException(documentId));

        if (document.state().resolved()) {
            // Issued, or recorded as having no provider path. Refused rather than
            // performed: asking a provider to fiscalize a leg it has already
            // fiscalized is the one action that could produce a second sale receipt
            // with the tax authority, and that is corrected rather than deleted.
            throw new NotRetryableException(document.state(), document.reasonCode());
        }

        if (document.legalEntityId() == null) {
            // The other half of the double-issue rule, and the half an operator can
            // reach by hand. Neither provider takes a seller identity as a request
            // field — the Click service and the Payme cashbox are the taxpayer — so
            // a document that names no company would be receipted under whichever
            // merchant account the implementation happens to resolve. The fix is an
            // ADR 0038 fiscal assignment for the branch, not a button press.
            throw new NoSellerException(documentId);
        }

        // The attempt is claimed before the provider is asked, and that ordering is
        // the whole of the concurrency story. Two operators pressing the button in
        // the same second both read the same version; one wins this conditional
        // update and the other is refused here, having sent nothing. Asking first
        // and counting afterwards would let both reach Click, which is how one
        // payment acquires two sale receipts — a discrepancy with the tax authority
        // that can only be corrected, never withdrawn.
        boolean counted = documents.recordRetryAttempt(tenantId, documentId, expectedVersion, clock.instant());
        if (!counted) {
            throw new StaleDocumentException(expectedVersion, document.version());
        }

        PartnerFiscalizationPort.Outcome outcome = partner.retry(tenantId, documentId, idempotencyKey);

        audit.record(AuditFact.of("fiscal.document.retry", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("fiscal_document", documentId)
                .targetVersion((long) expectedVersion)
                .outcome(
                        outcome == PartnerFiscalizationPort.Outcome.NOT_WIRED
                                ? AuditFact.Outcome.FAILED
                                : AuditFact.Outcome.SUCCEEDED)
                .because(reason)
                .changed(Map.of(
                        "outcome", outcome.name(), "orderId", document.orderId().toString()))
                .correlatedBy(correlationId == null ? documentId.toString() : correlationId)
                .occurredAt(clock.instant())
                .build());

        return new RetryResult(documentId, outcome, expectedVersion + 1);
    }

    /**
     * Returns a blocked document to the queue because the thing blocking it is
     * fixed.
     *
     * <p>ADR 0038's {@code BLOCKED -> PENDING} arrow, and the only way out of
     * {@code BLOCKED} that does not involve a provider. It does not assert that a
     * receipt exists — only that the obstacle is gone and the document may be
     * asked for again. The deadline is cleared with it, so the next submission
     * gets a full window rather than being blocked again by the deadline that was
     * already in the past.
     */
    @Transactional
    public boolean reopen(
            UUID tenantId,
            UUID documentId,
            int expectedVersion,
            ActorRef actor,
            String reason,
            @Nullable String correlationId) {

        FiscalDocumentRow document =
                documents.find(tenantId, documentId).orElseThrow(() -> new UnknownDocumentException(documentId));

        if (document.state() != FiscalDocumentState.BLOCKED) {
            throw new NotRetryableException(document.state(), document.reasonCode());
        }

        Instant now = clock.instant();
        boolean reopened = documents.reopen(
                tenantId,
                documentId,
                expectedVersion,
                FiscalReasonCode.AWAITING_PROVIDER,
                "unblocked by an operator: " + reason,
                now);
        if (!reopened) {
            throw new StaleDocumentException(expectedVersion, document.version());
        }

        audit.record(AuditFact.of("fiscal.document.unblock", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("fiscal_document", documentId)
                .targetVersion((long) expectedVersion)
                .because(reason)
                .changed(Map.of(
                        "fromReasonCode",
                        document.reasonCode(),
                        "orderId",
                        document.orderId().toString()))
                .correlatedBy(correlationId == null ? documentId.toString() : correlationId)
                .occurredAt(now)
                .build());

        return true;
    }

    /** What one operator retry produced. */
    public record RetryResult(UUID documentId, PartnerFiscalizationPort.Outcome outcome, int version) {}

    /** The document named does not exist for this tenant. */
    public static class UnknownDocumentException extends RuntimeException {
        public UnknownDocumentException(UUID documentId) {
            super("No fiscal document " + documentId + " for this tenant");
        }
    }

    /** The document is in a state from which this command is not available. */
    public static class NotRetryableException extends RuntimeException {
        private final transient FiscalDocumentState state;

        public NotRetryableException(FiscalDocumentState state, String reasonCode) {
            super("A document that is %s (%s) is not available for this command".formatted(state, reasonCode));
            this.state = state;
        }

        public FiscalDocumentState state() {
            return state;
        }
    }

    /**
     * The document names no legal entity, so nobody can be printed on the receipt
     * as the seller.
     */
    public static class NoSellerException extends RuntimeException {
        public NoSellerException(UUID documentId) {
            super(("Fiscal document %s names no legal entity. A receipt is issued under the "
                            + "selling company's own merchant account, so this needs an ADR 0038 fiscal "
                            + "assignment for the branch before it can be sent.")
                    .formatted(documentId));
        }
    }

    /** Somebody else changed the document between the read and the command. */
    public static class StaleDocumentException extends RuntimeException {
        private final int expected;
        private final int actual;

        public StaleDocumentException(int expected, int actual) {
            super("Expected fiscal document version %d, found %d".formatted(expected, actual));
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }

    private ZoneId zoneOf(ReportingCandidate candidate) {
        return BusinessZone.resolve(candidate.businessZone(), fallbackZone, "Document " + candidate.id());
    }

    private static String reasonNote(ReportingCandidate candidate, ReportingDeadline deadline) {
        String provider = candidate.providerType() == null ? "the provider" : candidate.providerType();
        return deadline.backstopped()
                ? "%s did not report by the end of the business date".formatted(provider)
                : "%s did not report within the reporting deadline".formatted(provider);
    }
}
