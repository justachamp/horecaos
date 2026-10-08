package uz.horecaos.platform.fiscal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalCorrectionStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalCorrectionStore.AttachedReference;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.CorrectionRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.FiscalDocumentRow;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Correction and void requests: HorecaOS records, the tenant's own fiscal agent issues
 * (ADR 0166, Decision 8).
 *
 * <p>An operator with {@code fiscal.document.resolve} raises a request against an issued sale
 * with a reason, under an ADR 0027 approval, and the document moves to {@code
 * CORRECTION_REQUESTED} or {@code VOID_REQUESTED}. The agent issues the correction on the path the
 * sale used (Payme's {@code CANCEL} is a second receipt, Click's reversal exists only inside the
 * reporting month, a wrong receipt is otherwise corrected in the provider's cabinet or on the
 * tenant's own equipment), and the operator attaches the new reference, which creates a linked
 * {@code CORRECTION} or {@code REFUND} document and moves the original to {@code CORRECTED} or
 * {@code VOIDED}.
 *
 * <p><strong>What this never does.</strong> It never edits or deletes a fiscal document, never
 * composes a correction receipt, and never calls a provider: making HorecaOS the issuer by the back
 * door, with a tax-authority correction it cannot take back, is the option ADR 0166 declines. It
 * enforces no deadline either; the legal deadline per path is not HorecaOS's to decide, so the
 * request is aged on a worklist and the question stays with finance.
 *
 * <p>The approval is a second person, and it is spent in the same transaction as the move it
 * authorises, so one signature covers one request. A request waiting for its checker is answered by
 * the caller (the controller maps it to {@code SECOND_APPROVER_REQUIRED} <em>after</em> this
 * transaction commits), because the approval request this method opened must survive the answer.
 */
@Service
public class FiscalCorrectionService {

    /** What an operator asks the agent to do. */
    public enum Kind {
        CORRECTION(FiscalDocumentState.CORRECTION_REQUESTED, FiscalDocumentState.CORRECTED, "CORRECTION"),
        VOID(FiscalDocumentState.VOID_REQUESTED, FiscalDocumentState.VOIDED, "REFUND");

        private final FiscalDocumentState requested;
        private final FiscalDocumentState done;
        private final String linkedDocumentType;

        Kind(FiscalDocumentState requested, FiscalDocumentState done, String linkedDocumentType) {
            this.requested = requested;
            this.done = done;
            this.linkedDocumentType = linkedDocumentType;
        }
    }

    /** Why, as a closed set a worklist can group by. The sentence that explains it is the note. */
    public enum Reason {
        WRONG_AMOUNT,
        WRONG_LINES,
        WRONG_CLASSIFICATION,
        WRONG_SELLER,
        DUPLICATE_RECEIPT,
        CUSTOMER_REFUND,
        OTHER
    }

    /** The decision the approval model came back with, or the request it allowed. */
    public sealed interface RequestResult {

        /** The request exists and the document has moved. */
        record Raised(CorrectionRow request, int documentVersion) implements RequestResult {}

        /** The action needs a second person, or the second person said no. Nothing else happened. */
        record Awaiting(ApprovalOutcome outcome) implements RequestResult {}
    }

    /** What the second person signs: the document, the kind of request, and why. Public so the hash can read it. */
    public record Proposal(UUID tenantId, UUID documentId, String kind, String reasonCode) {}

    private final JdbcFiscalLifecycleStore documents;
    private final JdbcFiscalCorrectionStore corrections;
    private final JdbcFiscalEvidenceStore evidence;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;

    public FiscalCorrectionService(
            JdbcFiscalLifecycleStore documents,
            JdbcFiscalCorrectionStore corrections,
            JdbcFiscalEvidenceStore evidence,
            ApprovalService approvals,
            AuditRecorder audit,
            Clock clock) {
        this.documents = documents;
        this.corrections = corrections;
        this.evidence = evidence;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RequestResult request(
            UUID tenantId,
            UUID documentId,
            int expectedVersion,
            Kind kind,
            Reason reason,
            String note,
            ActorRef actor,
            @Nullable String correlationId) {

        FiscalDocumentRow document = documents
                .find(tenantId, documentId)
                .orElseThrow(() -> new FiscalDocumentService.UnknownDocumentException(documentId));

        if (!"SALE".equals(document.documentType()) || document.state() != FiscalDocumentState.ISSUED) {
            // Only an issued sale is asked to be corrected. A receipt that was never issued has
            // nothing to correct (retry it instead), one already being corrected is being corrected,
            // and a refund or a correction is itself the answer to an earlier request.
            throw new NotCorrectableException(document.state(), document.documentType());
        }
        if (document.version() != expectedVersion) {
            throw new FiscalDocumentService.StaleDocumentException(expectedVersion, document.version());
        }

        ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.FISCAL_CORRECTION_REQUEST.code(),
                ApprovalParameters.of(new Proposal(tenantId, documentId, kind.name(), reason.name()))
                        .excluding()
                        .hash(),
                ResourceScope.tenant(tenantId),
                actor,
                note,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (!outcome.mayProceed()) {
            return new RequestResult.Awaiting(outcome);
        }
        // Spent in this transaction before the document moves, so one signature is one request.
        outcome.consume();
        UUID approvalId = approvalIdOf(outcome);

        Instant now = clock.instant();
        UUID requestId = UUID.randomUUID();
        try {
            corrections.insertRequest(
                    requestId,
                    tenantId,
                    documentId,
                    kind.name(),
                    reason.name(),
                    note,
                    actor.subject(),
                    approvalId,
                    now);
        } catch (DuplicateKeyException alreadyOpen) {
            // uq_fiscal_correction_one_open_per_document: two people asking for one correction is
            // one correction. The document is no longer ISSUED once a request is open, so this is
            // the race the status check above cannot see.
            throw new NotCorrectableException(document.state(), document.documentType());
        }
        if (!corrections.moveDocument(
                tenantId, documentId, FiscalDocumentState.ISSUED.name(), kind.requested.name(), expectedVersion, now)) {
            throw new FiscalDocumentService.StaleDocumentException(expectedVersion, document.version());
        }

        audit.record(AuditFact.of("fiscal.correction.requested", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("fiscal_document", documentId)
                .targetVersion((long) expectedVersion)
                .because(note)
                .underApproval(approvalId)
                .changed(ChangeDocuments.diff(
                        Map.of(
                                "status",
                                FiscalDocumentState.ISSUED.name(),
                                "orderId",
                                document.orderId().toString()),
                        Map.of(
                                "status", kind.requested.name(),
                                "kind", kind.name(),
                                "reasonCode", reason.name(),
                                "requestId", requestId.toString(),
                                "orderId", document.orderId().toString())))
                .correlatedBy(correlationId == null ? documentId.toString() : correlationId)
                .occurredAt(now)
                .build());

        CorrectionRow created = evidence.findCorrection(tenantId, requestId).orElseThrow();
        return new RequestResult.Raised(created, expectedVersion + 1);
    }

    /**
     * Records the reference the tenant's agent returned: creates the linked document and moves the
     * original and the request to their last status.
     *
     * @param expectedVersion the version of the <em>original</em> document the operator read; the
     *     request is closed at the version it was read at inside this transaction
     */
    @Transactional
    public Attached attach(
            UUID tenantId,
            UUID requestId,
            int expectedVersion,
            AttachedReference reference,
            ActorRef actor,
            @Nullable String correlationId) {

        CorrectionRow request =
                evidence.findCorrection(tenantId, requestId).orElseThrow(() -> new UnknownRequestException(requestId));
        if (!"REQUESTED".equals(request.status())) {
            throw new RequestNotOpenException(request.status());
        }
        FiscalDocumentRow original = documents
                .find(tenantId, request.documentId())
                .orElseThrow(() -> new FiscalDocumentService.UnknownDocumentException(request.documentId()));
        Kind kind = Kind.valueOf(request.kind());
        if (original.state() != kind.requested) {
            throw new NotCorrectableException(original.state(), original.documentType());
        }
        if (original.version() != expectedVersion) {
            throw new FiscalDocumentService.StaleDocumentException(expectedVersion, original.version());
        }

        Instant now = clock.instant();
        UUID linkedId = UUID.randomUUID();
        corrections.insertLinkedDocument(
                linkedId,
                tenantId,
                original.id(),
                kind.linkedDocumentType,
                kind == Kind.CORRECTION ? "CORRECTION_ATTACHED" : "VOID_ATTACHED",
                "the tenant's fiscal agent issued it; the operator attached its reference",
                reference,
                now);
        if (!corrections.moveDocument(
                tenantId, original.id(), kind.requested.name(), kind.done.name(), expectedVersion, now)) {
            throw new FiscalDocumentService.StaleDocumentException(expectedVersion, original.version());
        }
        if (!corrections.attachRequest(tenantId, requestId, request.version(), linkedId, actor.subject(), now)) {
            throw new RequestNotOpenException("CHANGED");
        }

        audit.record(AuditFact.of("fiscal.correction.attached", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("fiscal_document", original.id())
                .targetVersion((long) expectedVersion)
                .because("The agent's reference was attached to the "
                        + request.kind().toLowerCase(java.util.Locale.ROOT) + " request")
                .underApproval(request.approvalId())
                .changed(ChangeDocuments.diff(
                        Map.of(
                                "status",
                                kind.requested.name(),
                                "orderId",
                                original.orderId().toString()),
                        Map.of(
                                "status", kind.done.name(),
                                "kind", kind.name(),
                                "reasonCode", request.reasonCode(),
                                "requestId", requestId.toString(),
                                "linkedDocumentId", linkedId.toString(),
                                "orderId", original.orderId().toString())))
                .correlatedBy(correlationId == null ? original.id().toString() : correlationId)
                .occurredAt(now)
                .build());

        CorrectionRow attached = evidence.findCorrection(tenantId, requestId).orElseThrow();
        return new Attached(attached, linkedId, expectedVersion + 1);
    }

    /** What an attachment produced. */
    public record Attached(CorrectionRow request, UUID linkedDocumentId, int documentVersion) {}

    private static @Nullable UUID approvalIdOf(ApprovalOutcome outcome) {
        return outcome instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;
    }

    // ------------------------------------------------------------- refusals

    /** The document is not an issued sale, or is already being corrected. */
    public static class NotCorrectableException extends RuntimeException {
        private final transient FiscalDocumentState state;

        public NotCorrectableException(FiscalDocumentState state, String documentType) {
            super("A %s that is %s cannot be the subject of a new correction request".formatted(documentType, state));
            this.state = state;
        }

        public FiscalDocumentState state() {
            return state;
        }
    }

    /** No such request for this tenant. */
    public static class UnknownRequestException extends RuntimeException {
        public UnknownRequestException(UUID requestId) {
            super("No correction request " + requestId + " for this tenant");
        }
    }

    /** The request is not open any more: attached, declined, cancelled, or changed under the caller. */
    public static class RequestNotOpenException extends RuntimeException {
        private final String status;

        public RequestNotOpenException(String status) {
            super("A correction request that is %s cannot take an attachment".formatted(status));
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}
