package uz.qoida.platform.fiscal.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.fiscal.api.PartnerFiscalizationPort;
import uz.qoida.platform.fiscal.application.FiscalDocumentService;
import uz.qoida.platform.fiscal.domain.FiscalCoverage;
import uz.qoida.platform.fiscal.domain.FiscalReasonCode;
import uz.qoida.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.FiscalDocumentRow;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.CurrentActor;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.web.api.AggregateVersion;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * The blocked worklist, the coverage report, and the two things an operator can
 * do about a document that has neither a receipt nor an answer (ADR 0038).
 *
 * <p>At {@code TENANT} scope, and not lower. A fiscal obligation belongs to a
 * legal entity, which cuts across brands — one company routinely runs three
 * brands, and one brand is routinely split across two companies — so there is no
 * branch path that would contain the question. ADR 0025 scopes cover downwards,
 * so this asks for the grant that actually reaches the whole answer rather than
 * one that reaches part of it.
 *
 * <p>No fiscal sign, no receipt URL and no marking code appears in any response
 * here. Those are ADR 0029 protected evidence and are read through the payments
 * module's authorized order-payment view, with a recorded purpose. A worklist
 * needs to know that a document has evidence, not what the evidence says.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/fiscal")
@Tag(name = "Fiscal documents",
        description = "Unreported receipts, blocked work, and receipt coverage")
public class FiscalDocumentController {

    private static final int MAX_WINDOW_DAYS = 92;

    private final FiscalDocumentService documents;
    private final CurrentActor currentActor;

    public FiscalDocumentController(FiscalDocumentService documents, CurrentActor currentActor) {
        this.documents = documents;
        this.currentActor = currentActor;
    }

    @GetMapping("/documents/blocked")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Documents that owe a receipt and are waiting for a person",
            description = "Longest-waiting first. A blocked document is work with a reason, not "
                    + "an error: PROVIDER_REPORT_OVERDUE means a payment partner was asked and "
                    + "never answered, which on Payme's inbound-only reporting path is otherwise "
                    + "indistinguishable from a receipt that does not exist.")
    public ResponseEntity<BlockedWorklistResponse> blocked(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(defaultValue = "100") @Max(500) int limit) {

        if (reasonCode != null && !FiscalReasonCode.BLOCKING.contains(reasonCode)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Not a blocking reason code", Map.of("reasonCode", reasonCode));
        }

        List<BlockedDocumentResponse> rows = documents.blocked(tenantId, reasonCode, limit).stream()
                .map(BlockedDocumentResponse::of)
                .toList();

        return ResponseEntity.ok(new BlockedWorklistResponse(rows.size(), rows,
                documents.partnerFiscalizationWired()
                        ? null
                        : PartnerFiscalizationPort.NOT_WIRED_WARNING));
    }

    @GetMapping("/orders/{orderId}/documents")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Every fiscal document for one order",
            description = "A list, always. One order has several documents by design: a Payme "
                    + "PERFORM and its CANCEL are two receipts by the provider's own statement, "
                    + "and a split tender settles on two paths. A caller that expects one is "
                    + "reading a cancellation as though it replaced the sale.")
    public ResponseEntity<List<BlockedDocumentResponse>> forOrder(
            @PathVariable UUID tenantId, @PathVariable UUID orderId) {

        return ResponseEntity.ok(documents.forOrder(tenantId, orderId).stream()
                .map(BlockedDocumentResponse::of)
                .toList());
    }

    @GetMapping("/coverage")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_READ, scope = ScopeType.TENANT)
    @Operation(summary = "How much of a window's trade has a provider receipt",
            description = "Reported as counts and two separate shares, never as one coverage "
                    + "figure. Cash is this market's majority tender and no payment provider can "
                    + "receipt it at all, so a single number would either report an unreceipted "
                    + "majority as healthy or a compliant restaurant as failing.")
    public ResponseEntity<CoverageResponse> coverage(
            @PathVariable UUID tenantId,
            @RequestParam Instant from,
            @RequestParam Instant to) {

        if (!to.isAfter(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The window must end after it starts");
        }
        if (from.plus(MAX_WINDOW_DAYS, ChronoUnit.DAYS).isBefore(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A coverage window is at most %d days".formatted(MAX_WINDOW_DAYS));
        }

        return ResponseEntity.ok(CoverageResponse.of(documents.coverage(tenantId, from, to)));
    }

    @PostMapping("/documents/{documentId}/retries")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_RESOLVE, scope = ScopeType.TENANT,
            mutating = true)
    @Operation(summary = "Ask the partner for this document's receipt again",
            description = "Reuses the document and never creates a second one: two sale receipts "
                    + "for one payment is a discrepancy with the tax authority that can only be "
                    + "corrected, never deleted. On the Click path the adapter reads back through "
                    + "GET payment/ofd_data first — a populated qrCodeURL means the earlier "
                    + "submission worked — because Click does not document submit_items as "
                    + "idempotent.")
    public ResponseEntity<ResolutionResponse> retry(
            @PathVariable UUID tenantId, @PathVariable UUID documentId,
            @Valid @RequestBody ResolutionRequest body, HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        String idempotencyKey = requireIdempotencyKey(request);

        try {
            var result = documents.retry(tenantId, documentId, (int) expected, idempotencyKey,
                    actor(), body.reason(), request.getHeader("X-Correlation-Id"));
            return ResponseEntity.ok(new ResolutionResponse(documentId, result.outcome().name(),
                    result.version(),
                    result.outcome() == PartnerFiscalizationPort.Outcome.NOT_WIRED
                            ? PartnerFiscalizationPort.NOT_WIRED_WARNING
                            : null));
        } catch (FiscalDocumentService.UnknownDocumentException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (FiscalDocumentService.NotRetryableException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage(),
                    Map.of("status", refused.state().name()));
        } catch (FiscalDocumentService.NoSellerException unsellable) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, unsellable.getMessage(),
                    Map.of("reasonCode", FiscalReasonCode.NO_FISCAL_PATH));
        } catch (FiscalDocumentService.StaleDocumentException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
    }

    @PostMapping("/documents/{documentId}/unblocks")
    @RequiresCapability(value = Capability.FISCAL_DOCUMENT_RESOLVE, scope = ScopeType.TENANT,
            mutating = true)
    @Operation(summary = "Return a blocked document to the queue",
            description = "For the case where the thing blocking it is fixed — a classification "
                    + "entered, a terminal brought back. It asserts that the obstacle is gone, "
                    + "not that a receipt exists, so the document goes back to PENDING and its "
                    + "deadline is cleared rather than being marked resolved.")
    public ResponseEntity<ResolutionResponse> unblock(
            @PathVariable UUID tenantId, @PathVariable UUID documentId,
            @Valid @RequestBody ResolutionRequest body, HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        requireIdempotencyKey(request);

        try {
            documents.reopen(tenantId, documentId, (int) expected, actor(), body.reason(),
                    request.getHeader("X-Correlation-Id"));
            return ResponseEntity.ok(new ResolutionResponse(documentId, "PENDING",
                    (int) expected + 1, null));
        } catch (FiscalDocumentService.UnknownDocumentException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (FiscalDocumentService.NotRetryableException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage(),
                    Map.of("status", refused.state().name()));
        } catch (FiscalDocumentService.StaleDocumentException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
    }

    private ActorRef actor() {
        var authenticated = currentActor.get();
        return ActorRef.user(authenticated.subject(), null);
    }

    private static String requireIdempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "A fiscal resolution command carries an Idempotency-Key (ADR 0031)");
        }
        return key;
    }

    /** Every command here records why, because ADR 0027 refuses a user action without one. */
    record ResolutionRequest(@NotBlank @Size(max = 255) String reason) { }

    record ResolutionResponse(UUID documentId, String outcome, int version, String warning) { }

    /**
     * @param warning present when no provider adapter is wired, so the gap appears
     *                on every read rather than in a startup log nobody sees again
     */
    record BlockedWorklistResponse(int count, List<BlockedDocumentResponse> documents,
            String warning) { }

    /**
     * @param hasEvidence whether the tax authority's identifiers are on the row.
     *                    Whether, not what: the identifiers themselves are ADR 0029
     *                    evidence and are not a worklist's business
     */
    record BlockedDocumentResponse(
            UUID documentId,
            UUID orderId,
            UUID legalEntityId,
            String documentType,
            String responsibility,
            String providerType,
            String status,
            String reasonCode,
            String reasonNote,
            boolean hasEvidence,
            int attemptCount,
            int version,
            Instant submittedAt,
            Instant reportingDeadlineAt,
            Instant blockedAt) {

        static BlockedDocumentResponse of(FiscalDocumentRow row) {
            return new BlockedDocumentResponse(row.id(), row.orderId(), row.legalEntityId(),
                    row.documentType(), row.responsibility(), row.providerType(),
                    row.state().name(), row.reasonCode(), row.reasonNote(), row.hasEvidence(),
                    row.attemptCount(), row.version(), row.submittedAt(),
                    row.reportingDeadlineAt(), row.blockedAt());
        }
    }

    /**
     * @param notApplicableShareBasisPoints the share no provider can receipt —
     *                                      overwhelmingly cash. Reported beside the
     *                                      issued share and never folded into it
     * @param providerPathIsMinority        ADR 0038 predicts this stays true for the
     *                                      whole pilot. A prediction nobody checks is
     *                                      a prediction nobody notices coming true
     */
    record CoverageResponse(
            Instant from,
            Instant to,
            long saleDocuments,
            long issued,
            long notApplicable,
            long notApplicableCash,
            long blocked,
            long failed,
            long awaitingProvider,
            long unreceipted,
            int issuedShareBasisPoints,
            int notApplicableShareBasisPoints,
            int unreceiptedShareBasisPoints,
            boolean providerPathIsMinority,
            String warning) {

        static CoverageResponse of(FiscalCoverage coverage) {
            return new CoverageResponse(coverage.from(), coverage.to(), coverage.saleDocuments(),
                    coverage.issued(), coverage.notApplicable(), coverage.notApplicableCash(),
                    coverage.blocked(), coverage.failed(), coverage.awaitingProvider(),
                    coverage.unreceipted(), coverage.issuedBasisPoints(),
                    coverage.notApplicableBasisPoints(), coverage.unreceiptedBasisPoints(),
                    coverage.providerPathIsMinority(),
                    coverage.partnerFiscalizationWired()
                            ? null
                            : PartnerFiscalizationPort.NOT_WIRED_WARNING);
        }
    }
}
