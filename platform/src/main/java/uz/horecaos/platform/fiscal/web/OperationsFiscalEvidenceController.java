package uz.horecaos.platform.fiscal.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.fiscal.application.FiscalCorrectionService;
import uz.horecaos.platform.fiscal.application.FiscalDocumentService;
import uz.horecaos.platform.fiscal.application.FiscalEvidenceService;
import uz.horecaos.platform.fiscal.application.FiscalEvidenceService.Purpose;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalCorrectionStore.AttachedReference;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.CorrectionRow;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The fiscal evidence surface: what an operator handed a customer complaint or a tax query can
 * produce, and the requests that fix a wrong receipt (ADR 0166).
 *
 * <p>At {@code TENANT} scope and not lower, for the reason {@link FiscalDocumentController}
 * argues: an obligation belongs to a legal entity, which cuts across brands. The earlier
 * controller still answers {@code hasEvidence} and nothing else; this is the audited read it
 * pointed at.
 *
 * <p><strong>Identifiers are masked unless asked for in full, with a reason.</strong> Sending a
 * {@code purpose} is the request to see them. It needs {@code fiscal.evidence.reveal} (403
 * without it), a purpose from a closed set (422 without one), and writes a {@code SECURITY} audit
 * fact per document naming the fields and the purpose. No marking code is in any response, under
 * any purpose: marking is shown as a count, ADR 0161's rule.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(
        name = "Fiscal evidence",
        description = "The receipts filed for an order, which INN issued them, the lines as filed, "
                + "and requests to correct or void one")
public class OperationsFiscalEvidenceController {

    private static final int MAX_WORKLIST = 500;

    private final FiscalEvidenceService evidence;
    private final FiscalCorrectionService corrections;
    private final OrderDirectory orders;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;

    public OperationsFiscalEvidenceController(
            FiscalEvidenceService evidence,
            FiscalCorrectionService corrections,
            OrderDirectory orders,
            AuthorizationService authorization,
            CurrentActor currentActor) {
        this.evidence = evidence;
        this.corrections = corrections;
        this.orders = orders;
        this.authorization = authorization;
        this.currentActor = currentActor;
    }

    // ----------------------------------------------------------------- evidence

    @GetMapping("/orders/{orderId}/fiscal-evidence")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The receipts filed for one order, with the evidence on each",
            description = "A list of documents, always: a Payme PERFORM and its CANCEL are two receipts, "
                    + "and a split tender settles on two paths. Each document says which INN issued it "
                    + "(the one on the document when it was opened, never the entity's today: a "
                    + "BACKFILL source is weaker and says so, UNRESOLVED means no entity), under which "
                    + "responsibility and merchant account, the lines as filed with their ИКПУ and "
                    + "package code, how the provider was told the payment was split, and the number "
                    + "of marking codes transmitted. Identifiers (fiscal sign, receipt number, receipt "
                    + "link, terminal id) are masked to their last four characters. Sending purpose "
                    + "(TAX_INQUIRY, CUSTOMER_COMPLAINT, AUDIT or CORRECTION) asks for them in full: "
                    + "that needs fiscal.evidence.reveal (403 without it), a purpose from that set "
                    + "(422 PURPOSE_REQUIRED without one), and writes one SECURITY audit fact per "
                    + "document. A marking code is never returned, whatever the purpose. No receipt is "
                    + "drawn: the console shows identifiers, a link to the OFD and the lines as filed.")
    public ResponseEntity<FiscalEvidenceResponse> forOrder(
            @PathVariable UUID tenantId,
            @PathVariable UUID orderId,
            @RequestParam(required = false) @Nullable String purpose,
            HttpServletRequest request) {

        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        Purpose reveal = null;
        if (purpose != null) {
            // The capability first, then the purpose: a caller who may not look at all is told so
            // without being taught which purposes exist.
            requireReveal(tenantId);
            reveal = Purpose.parse(purpose)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.PURPOSE_REQUIRED,
                            "Opening a fiscal identifier in full needs a purpose: TAX_INQUIRY, "
                                    + "CUSTOMER_COMPLAINT, AUDIT or CORRECTION"));
        }

        return ResponseEntity.ok(FiscalEvidenceResponse.of(
                order.publicOrderNumber(),
                evidence.forOrder(tenantId, orderId, reveal, actor(), request.getHeader("X-Correlation-Id"))));
    }

    private void requireReveal(UUID tenantId) {
        String subject = currentActor.get().subject();
        if (!authorization.has(subject, Capability.FISCAL_EVIDENCE_REVEAL, ResourceScope.tenant(tenantId))) {
            throw ApiException.insufficientCapability(Capability.FISCAL_EVIDENCE_REVEAL.code(), "TENANT");
        }
    }

    @GetMapping("/fiscal/payment-types")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Which payment types are fiscalized, derived and not settable",
            description = "One row per payment method in the tenant's registry: who issues its receipt "
                    + "(the responsibility), under which legal entity's merchant account or terminal, "
                    + "and whether that path is live today or NO_FISCAL_PATH. Derived from the "
                    + "registry, the merchant bindings and the terminal register, and there is no way "
                    + "to set it: a setting that disagreed with the bindings would be a lie on a tax "
                    + "document. A method with no path is an unevidenced obligation of the tenant, "
                    + "never reported as HorecaOS fiscalizing it.")
    public ResponseEntity<PaymentTypesResponse> paymentTypes(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(new PaymentTypesResponse(evidence.paymentTypes(tenantId).stream()
                .map(PaymentTypeResponse::of)
                .toList()));
    }

    // ------------------------------------------------------------- corrections

    @GetMapping("/fiscal/correction-requests")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Correction and void requests still waiting for the agent, longest first",
            description = "HorecaOS records these and the tenant's own fiscal agent issues the "
                    + "correction, so the list is aged and not alarmed: the legal deadline per path "
                    + "is not enforced here.")
    public ResponseEntity<List<CorrectionRequestResponse>> openRequests(
            @PathVariable UUID tenantId, @RequestParam(defaultValue = "100") @Max(MAX_WORKLIST) int limit) {
        return ResponseEntity.ok(evidence.openCorrections(tenantId, limit).stream()
                .map(CorrectionRequestResponse::of)
                .toList());
    }

    @PostMapping("/fiscal/documents/{documentId}/correction-requests")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_RESOLVE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Ask the tenant's fiscal agent to correct or void an issued receipt",
            description = "Records the request; HorecaOS never issues a correction, edits a filed "
                    + "document or calls a provider to do it. Under an ADR 0027 approval: the first "
                    + "call answers 422 SECOND_APPROVER_REQUIRED naming approvalRequestId, and once a "
                    + "second person has approved it the same request goes through. Only an ISSUED "
                    + "sale can be asked about, once at a time. The document moves to "
                    + "CORRECTION_REQUESTED or VOID_REQUESTED; If-Match is its version.")
    public ResponseEntity<CorrectionRequestedResponse> request(
            @PathVariable UUID tenantId,
            @PathVariable UUID documentId,
            @Valid @RequestBody CorrectionRequestBody body,
            HttpServletRequest httpRequest) {

        long expected = AggregateVersion.requireIfMatch(httpRequest);
        FiscalCorrectionService.RequestResult result;
        try {
            result = corrections.request(
                    tenantId,
                    documentId,
                    (int) expected,
                    body.kind(),
                    body.reasonCode(),
                    body.reasonNote().strip(),
                    actor(),
                    httpRequest.getHeader("X-Correlation-Id"));
        } catch (FiscalDocumentService.UnknownDocumentException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (FiscalCorrectionService.NotCorrectableException refused) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    refused.getMessage(),
                    Map.of("status", refused.state().name()));
        } catch (FiscalDocumentService.StaleDocumentException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }

        return switch (result) {
            case FiscalCorrectionService.RequestResult.Raised raised ->
                ResponseEntity.ok(CorrectionRequestedResponse.of(raised.request(), raised.documentVersion()));
            case FiscalCorrectionService.RequestResult.Awaiting awaiting -> throw awaitingApproval(awaiting.outcome());
        };
    }

    @PostMapping("/fiscal/correction-requests/{requestId}/attachments")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_RESOLVE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Attach the reference the agent returned, closing a correction or void request",
            description = "Creates the linked CORRECTION or REFUND document from the reference the "
                    + "tenant's agent filed and moves the original to CORRECTED or VOIDED. It never "
                    + "edits the original's evidence. If-Match is the original document's version.")
    public ResponseEntity<AttachedResponse> attach(
            @PathVariable UUID tenantId,
            @PathVariable UUID requestId,
            @Valid @RequestBody AttachmentBody body,
            HttpServletRequest httpRequest) {

        long expected = AggregateVersion.requireIfMatch(httpRequest);
        try {
            FiscalCorrectionService.Attached attached = corrections.attach(
                    tenantId,
                    requestId,
                    (int) expected,
                    new AttachedReference(
                            body.fiscalSign().strip(),
                            body.receiptNumber().strip(),
                            body.receiptUrl().strip(),
                            body.terminalId() == null || body.terminalId().isBlank()
                                    ? null
                                    : body.terminalId().strip(),
                            body.registeredAt()),
                    actor(),
                    httpRequest.getHeader("X-Correlation-Id"));
            return ResponseEntity.ok(AttachedResponse.of(attached));
        } catch (FiscalCorrectionService.UnknownRequestException
                | FiscalDocumentService.UnknownDocumentException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (FiscalCorrectionService.RequestNotOpenException closed) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, closed.getMessage(), Map.of("status", closed.status()));
        } catch (FiscalCorrectionService.NotCorrectableException refused) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    refused.getMessage(),
                    Map.of("status", refused.state().name()));
        } catch (FiscalDocumentService.StaleDocumentException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
    }

    /**
     * The 422 the maker is answered with while a checker has not decided: the request exists and
     * is named, and the remedy is to wait for it, not to change the call.
     */
    private static ApiException awaitingApproval(ApprovalOutcome outcome) {
        return switch (outcome) {
            case ApprovalOutcome.Pending pending ->
                new ApiException(
                        ErrorCode.SECOND_APPROVER_REQUIRED,
                        "Correcting a fiscal receipt needs a second approver. Approval request %s is pending."
                                .formatted(pending.requestId()),
                        Map.of("approvalRequestId", pending.requestId().toString(), "approvalStatus", "PENDING"));
            case ApprovalOutcome.Declined declined ->
                new ApiException(
                        ErrorCode.SECOND_APPROVER_REQUIRED,
                        "Approval request %s was declined, so this receipt is not corrected."
                                .formatted(declined.requestId()),
                        Map.of("approvalRequestId", declined.requestId().toString(), "approvalStatus", "DECLINED"));
            default -> new ApiException(ErrorCode.SECOND_APPROVER_REQUIRED, "A second approver is required");
        };
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    // =========================================================== wire shapes

    /** @param purpose set when the identifiers were opened in full, to the purpose they were opened for */
    public record FiscalEvidenceResponse(
            UUID orderId,
            @Nullable String publicOrderNumber,
            RetentionResponse retention,
            MarksResponse marks,
            boolean revealed,
            @Nullable String purpose,
            List<EvidenceDocumentResponse> documents) {

        static FiscalEvidenceResponse of(@Nullable String publicOrderNumber, FiscalEvidenceService.OrderEvidence view) {
            return new FiscalEvidenceResponse(
                    view.orderId(),
                    publicOrderNumber,
                    new RetentionResponse(
                            view.retention().years(), view.retention().provisional()),
                    new MarksResponse(
                            view.marks().state(),
                            view.marks().required(),
                            view.marks().captured()),
                    view.revealed(),
                    view.purpose() == null ? null : view.purpose().name(),
                    view.documents().stream().map(EvidenceDocumentResponse::of).toList());
        }
    }

    /** @param provisional true until the figure is confirmed by someone with the authority to */
    public record RetentionResponse(int years, boolean provisional) {}

    /**
     * Marking as counts.
     *
     * @param state {@code NOT_BUILT} when the platform has no mark store (no count is shown), else {@code COUNTS}
     */
    public record MarksResponse(
            String state,
            @Nullable Integer required,
            @Nullable Integer captured) {}

    public record SellerResponse(
            @Nullable UUID legalEntityId,
            @Nullable String tin,
            @Nullable String name,
            String source) {}

    public record IdentifiersResponse(
            boolean revealed,
            @Nullable String fiscalSign,
            @Nullable String receiptNumber,
            @Nullable String receiptUrl,
            @Nullable String terminalId) {}

    public record MerchantAccountResponse(
            UUID bindingId, @Nullable String accountReferenceMasked) {}

    public record LineResponse(
            String kind,
            String name,
            String ikpu,
            String packageCode,
            @Nullable Long unitCode,
            int quantity,
            ApiMoney unitPrice,
            ApiMoney tax,
            int vatPercent,
            @Nullable ApiMoney discount,
            @Nullable String barcode,
            int markingCodeCount) {}

    /** @param state AVAILABLE, NOT_SENT (nothing was sent, or it was not kept) or NOT_READABLE */
    public record LinesResponse(
            String state,
            boolean synthetic,
            List<LineResponse> items,
            @Nullable Integer markingCodesTransmitted) {}

    public record TenderSplitResponse(ApiMoney card, ApiMoney cash, ApiMoney ecash) {}

    public record CorrectionSummaryResponse(
            UUID requestId,
            String kind,
            String status,
            Instant requestedAt,
            @Nullable UUID attachedDocumentId) {}

    public record EvidenceDocumentResponse(
            UUID documentId,
            String documentType,
            @Nullable UUID correctsDocumentId,
            String status,
            String reasonCode,
            String reasonNote,
            String responsibility,
            @Nullable String providerType,
            SellerResponse seller,
            @Nullable MerchantAccountResponse merchantAccount,
            @Nullable UUID tenderId,
            @Nullable UUID paymentIntentId,
            boolean hasEvidence,
            IdentifiersResponse identifiers,
            @Nullable Instant registeredAt,
            @Nullable String providerStatusCode,
            LinesResponse lines,
            @Nullable TenderSplitResponse tenderSplit,
            @Nullable CorrectionSummaryResponse correction,
            @Nullable Instant submittedAt,
            @Nullable Instant issuedAt,
            @Nullable Instant blockedAt,
            int attemptCount,
            int version,
            Instant createdAt) {

        static EvidenceDocumentResponse of(FiscalEvidenceService.Document document) {
            var seller = document.seller();
            var identifiers = document.identifiers();
            var lines = document.lines();
            return new EvidenceDocumentResponse(
                    document.documentId(),
                    document.documentType(),
                    document.correctsDocumentId(),
                    document.status(),
                    document.reasonCode(),
                    document.reasonNote(),
                    document.responsibility(),
                    document.providerType(),
                    new SellerResponse(seller.legalEntityId(), seller.tin(), seller.name(), seller.source()),
                    document.merchantAccount() == null
                            ? null
                            : new MerchantAccountResponse(
                                    document.merchantAccount().bindingId(),
                                    document.merchantAccount().accountReferenceMasked()),
                    document.tenderId(),
                    document.paymentIntentId(),
                    document.hasEvidence(),
                    new IdentifiersResponse(
                            identifiers.revealed(),
                            identifiers.fiscalSign(),
                            identifiers.receiptNumber(),
                            identifiers.receiptUrl(),
                            identifiers.terminalId()),
                    document.registeredAt(),
                    document.providerStatusCode(),
                    new LinesResponse(
                            lines.state(),
                            lines.synthetic(),
                            lines.items().stream()
                                    .map(OperationsFiscalEvidenceController::line)
                                    .toList(),
                            lines.markingCodesTransmitted()),
                    document.tenderSplit() == null
                            ? null
                            : new TenderSplitResponse(
                                    money(document.tenderSplit().card()),
                                    money(document.tenderSplit().cash()),
                                    money(document.tenderSplit().ecash())),
                    document.correction() == null
                            ? null
                            : new CorrectionSummaryResponse(
                                    document.correction().requestId(),
                                    document.correction().kind(),
                                    document.correction().status(),
                                    document.correction().requestedAt(),
                                    document.correction().attachedDocumentId()),
                    document.submittedAt(),
                    document.issuedAt(),
                    document.blockedAt(),
                    document.attemptCount(),
                    document.version(),
                    document.createdAt());
        }
    }

    private static ApiMoney money(FiscalEvidenceService.Money value) {
        return ApiMoney.of(value.amountMinor(), value.currency());
    }

    private static LineResponse line(FiscalEvidenceService.Line line) {
        return new LineResponse(
                line.kind(),
                line.name(),
                line.ikpu(),
                line.packageCode(),
                line.unitCode(),
                line.quantity(),
                money(line.unitPrice()),
                money(line.tax()),
                line.vatPercent(),
                line.discount() == null ? null : money(line.discount()),
                line.barcode(),
                line.markingCodeCount());
    }

    public record PathEntryResponse(
            UUID legalEntityId,
            @Nullable String tin,
            @Nullable String legalName,
            @Nullable UUID bindingId,
            @Nullable String accountReferenceMasked,
            long capableTerminals,
            boolean supportsPartnerFiscalization) {}

    /**
     * @param path {@code PARTNER_ACCOUNT}, {@code TERMINAL}, {@code AGGREGATOR}, {@code NOT_A_FISCAL_PATH} or {@code NONE}
     * @param live whether a receipt for this method can be issued today
     * @param reasonCode {@code NO_FISCAL_PATH} when it cannot
     */
    public record PaymentTypeResponse(
            String code,
            String displayName,
            String methodStatus,
            String responsibility,
            String path,
            boolean live,
            @Nullable String reasonCode,
            @Nullable String note,
            List<PathEntryResponse> entries) {

        static PaymentTypeResponse of(FiscalEvidenceService.PaymentType type) {
            return new PaymentTypeResponse(
                    type.code(),
                    type.displayName(),
                    type.methodStatus(),
                    type.responsibility(),
                    type.path(),
                    type.live(),
                    type.reasonCode(),
                    type.note(),
                    type.entries().stream()
                            .map(entry -> new PathEntryResponse(
                                    entry.legalEntityId(),
                                    entry.tin(),
                                    entry.legalName(),
                                    entry.bindingId(),
                                    entry.accountReferenceMasked(),
                                    entry.capableTerminals(),
                                    entry.supportsPartnerFiscalization()))
                            .toList());
        }
    }

    public record PaymentTypesResponse(List<PaymentTypeResponse> types) {}

    public record CorrectionRequestBody(
            @NotNull FiscalCorrectionService.Kind kind,
            @NotNull FiscalCorrectionService.Reason reasonCode,
            @NotBlank @Size(max = 255) String reasonNote) {}

    /**
     * What the tenant's agent filed, as the operator attaches it: a reference and not a receipt.
     */
    public record AttachmentBody(
            @NotBlank @Size(max = 64) String fiscalSign,
            @NotBlank @Size(max = 64) String receiptNumber,

            @NotBlank @Size(max = 512) @Pattern(regexp = "^https://\\S+$")
            String receiptUrl,

            @Nullable @Size(max = 64) String terminalId,
            @Nullable Instant registeredAt) {}

    public record CorrectionRequestResponse(
            UUID requestId,
            UUID documentId,
            UUID orderId,
            String kind,
            String reasonCode,
            String status,
            String requestedBy,
            @Nullable UUID approvalId,
            Instant requestedAt,
            @Nullable UUID attachedDocumentId,
            @Nullable Instant attachedAt,
            int version) {

        static CorrectionRequestResponse of(CorrectionRow row) {
            return new CorrectionRequestResponse(
                    row.id(),
                    row.documentId(),
                    row.orderId(),
                    row.kind(),
                    row.reasonCode(),
                    row.status(),
                    row.requestedBy(),
                    row.approvalId(),
                    row.requestedAt(),
                    row.attachedDocumentId(),
                    row.attachedAt(),
                    row.version());
        }
    }

    public record CorrectionRequestedResponse(CorrectionRequestResponse request, int documentVersion) {

        static CorrectionRequestedResponse of(CorrectionRow row, int documentVersion) {
            return new CorrectionRequestedResponse(CorrectionRequestResponse.of(row), documentVersion);
        }
    }

    public record AttachedResponse(CorrectionRequestResponse request, UUID linkedDocumentId, int documentVersion) {

        static AttachedResponse of(FiscalCorrectionService.Attached attached) {
            return new AttachedResponse(
                    CorrectionRequestResponse.of(attached.request()),
                    attached.linkedDocumentId(),
                    attached.documentVersion());
        }
    }
}
