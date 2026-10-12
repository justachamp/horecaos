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
 * <p>A request has three ways out and no others: an attachment (the agent issued it), a closure as
 * {@code DECLINED} (the agent would not) and a closure as {@code CANCELLED} (it was raised in error). The
 * two closures file nothing and put the document back to {@code ISSUED}; before they existed the only exit
 * was to attach a reference, which an operator who merely wanted the request gone could only do by
 * recording a receipt nobody had issued.
 *
 * <p>The approval is a second person, and it is spent in the same transaction as the move it
 * authorises, so one signature covers one request. The attachment needs its own, bound to the reference
 * typed ({@link ApprovalAction#FISCAL_CORRECTION_ATTACH}): it files a fiscal document, moves the original
 * to {@code CORRECTED} or {@code VOIDED} and puts a link in front of the customer, on what one person typed. A request waiting for its checker is answered by
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

    /**
     * Why a request is closed without a receipt: the tenant's fiscal agent would not issue it, or a person
     * withdrew it.
     */
    public enum Closure {
        DECLINED,
        CANCELLED
    }

    /** The reason code of a linked {@code CORRECTION} document an attachment created. */
    public static final String CORRECTION_ATTACHED = "CORRECTION_ATTACHED";

    /** The reason code of a linked {@code REFUND} document an attachment created. */
    public static final String VOID_ATTACHED = "VOID_ATTACHED";

    /** The decision the approval model came back with, or the request it allowed. */
    public sealed interface RequestResult {

        /** The request exists and the document has moved. */
        record Raised(CorrectionRow request, int documentVersion) implements RequestResult {}

        /** The action needs a second person, or the second person said no. Nothing else happened. */
        record Awaiting(ApprovalOutcome outcome) implements RequestResult {}
    }

    /** What the second person signs: the document, the kind of request, and why. Public so the hash can read it. */
    public record Proposal(UUID tenantId, UUID documentId, String kind, String reasonCode) {}

    /**
     * What the second person signs when a reference is attached: the request and every field of the
     * reference. Public so the hash can read it. Typing a different number or link afterwards is a different
     * proposal, with a signature of its own.
     */
    public record AttachProposal(
            UUID tenantId,
            UUID requestId,
            String fiscalSign,
            String receiptNumber,
            String receiptUrl,
            @Nullable String terminalId,
            @Nullable Instant registeredAt) {}

    /** The attachment that was made, or the approval it is waiting for. */
    public sealed interface AttachResult {

        /** The reference is attached and the original has moved. */
        record Done(Attached attached) implements AttachResult {}

        /** The attachment needs a second person, or the second person said no. Nothing else happened. */
        record Awaiting(ApprovalOutcome outcome) implements AttachResult {}
    }

    private final JdbcFiscalLifecycleStore documents;
    private final JdbcFiscalCorrectionStore corrections;
    private final JdbcFiscalEvidenceStore evidence;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;
    private final FiscalReceiptLinks receiptLinks;

    public FiscalCorrectionService(
            JdbcFiscalLifecycleStore documents,
            JdbcFiscalCorrectionStore corrections,
            JdbcFiscalEvidenceStore evidence,
            ApprovalService approvals,
            AuditRecorder audit,
            Clock clock,
            FiscalReceiptLinks receiptLinks) {
        this.documents = documents;
        this.corrections = corrections;
        this.evidence = evidence;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
        this.receiptLinks = receiptLinks;
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
     * <p>Under a second person's approval, bound to the reference itself. An attachment is a fiscal
     * document HorecaOS records on what one person typed, moves the original to {@code CORRECTED} or {@code
     * VOIDED}, and shows the customer the link; the request's own approval said nothing about any of the
     * three. The link has to be the tax authority's, checked before an approval is asked for so that a
     * refused link costs the second person nothing to decide.
     *
     * @param expectedVersion the version of the <em>original</em> document the operator read; the
     *     request is closed at the version it was read at inside this transaction
     */
    @Transactional
    public AttachResult attach(
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
        if (!receiptLinks.isTaxAuthorityLink(reference.receiptUrl())) {
            throw new ReceiptLinkRefusedException();
        }
        String host = receiptLinks.hostOf(reference.receiptUrl()).orElseThrow();

        ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.FISCAL_CORRECTION_ATTACH.code(),
                ApprovalParameters.of(new AttachProposal(
                                tenantId,
                                requestId,
                                reference.fiscalSign(),
                                reference.receiptNumber(),
                                reference.receiptUrl(),
                                reference.terminalId(),
                                reference.registeredAt()))
                        .excluding()
                        .hash(),
                ResourceScope.tenant(tenantId),
                actor,
                // What the checker is shown to decide on: enough of the reference to compare with the
                // agent's own cabinet, masked as the evidence surface masks it (ADR 0166, Decision 5).
                "Attach the %s reference (receipt %s, link on %s) to the %s request %s"
                        .formatted(
                                kind == Kind.CORRECTION ? "correction" : "void",
                                FiscalEvidenceService.mask(reference.receiptNumber()),
                                host,
                                request.reasonCode(),
                                requestId),
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (!outcome.mayProceed()) {
            return new AttachResult.Awaiting(outcome);
        }
        // Spent in this transaction before anything is written, so one signature is one attachment.
        outcome.consume();
        UUID approvalId = approvalIdOf(outcome);

        Instant now = clock.instant();
        UUID linkedId = UUID.randomUUID();
        corrections.insertLinkedDocument(
                linkedId,
                tenantId,
                original.id(),
                kind.linkedDocumentType,
                kind == Kind.CORRECTION ? CORRECTION_ATTACHED : VOID_ATTACHED,
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
                .underApproval(approvalId)
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
        return new AttachResult.Done(new Attached(attached, linkedId, expectedVersion + 1));
    }

    /**
     * Closes an open request without a receipt: the tenant's agent declined to issue it, or it was raised in
     * error. The document returns to {@code ISSUED} with its evidence exactly as it was, and nothing is filed,
     * attached or invented. Without this a request had one exit, an attachment, and an operator who only
     * wanted the request off the worklist was left to record a reference nobody had issued.
     *
     * <p>One signature, not two. The approval guards what the tenant files with the tax authority and this
     * files nothing: it puts the document back where it was before anyone asked.
     *
     * @param expectedVersion the version of the <em>document</em> the operator read, as for an attachment
     */
    @Transactional
    public Closed close(
            UUID tenantId,
            UUID requestId,
            int expectedVersion,
            Closure closure,
            String note,
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
        if (!corrections.moveDocument(
                tenantId,
                original.id(),
                kind.requested.name(),
                FiscalDocumentState.ISSUED.name(),
                expectedVersion,
                now)) {
            throw new FiscalDocumentService.StaleDocumentException(expectedVersion, original.version());
        }
        if (!corrections.closeRequest(
                tenantId, requestId, request.version(), closure.name(), actor.subject(), note, now)) {
            throw new RequestNotOpenException("CHANGED");
        }

        audit.record(AuditFact.of("fiscal.correction.closed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("fiscal_document", original.id())
                .targetVersion((long) expectedVersion)
                .because(note)
                .changed(ChangeDocuments.diff(
                        Map.of(
                                "status",
                                kind.requested.name(),
                                "orderId",
                                original.orderId().toString()),
                        Map.of(
                                "status", FiscalDocumentState.ISSUED.name(),
                                "kind", kind.name(),
                                "closure", closure.name(),
                                "reasonCode", request.reasonCode(),
                                "requestId", requestId.toString(),
                                "orderId", original.orderId().toString())))
                .correlatedBy(correlationId == null ? original.id().toString() : correlationId)
                .occurredAt(now)
                .build());

        CorrectionRow closed = evidence.findCorrection(tenantId, requestId).orElseThrow();
        return new Closed(closed, expectedVersion + 1);
    }

    /** What a closure produced. */
    public record Closed(CorrectionRow request, int documentVersion) {}

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

    /** The link is not an https page of the tax authority's own site. Carries no link: a person typed it. */
    public static class ReceiptLinkRefusedException extends RuntimeException {
        public ReceiptLinkRefusedException() {
            super("The receipt link must be an https page of the tax authority's own site");
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
            super("A correction request that is %s is no longer open to an attachment or a closure".formatted(status));
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}
