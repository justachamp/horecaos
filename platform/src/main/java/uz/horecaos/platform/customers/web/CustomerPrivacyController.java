package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.customers.application.CustomerCorrectionService;
import uz.horecaos.platform.customers.application.CustomerCorrectionService.Outcome;
import uz.horecaos.platform.customers.application.CustomerErasureService;
import uz.horecaos.platform.customers.application.CustomerExportService;
import uz.horecaos.platform.customers.application.CustomerExportService.RequestedVia;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCorrectionStore.CorrectionRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore.ExportRequestRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcPrivacyRequestStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcPrivacyRequestStore.PrivacyRequestRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The data-subject operations beside erasure that the customer pane and Settings 10.11 drive (ADR 0029): a request for a
 * copy of her data and the bundle that answers it, a correction against evidence, and the one worklist that lists all three
 * kinds of request.
 *
 * <p>Raising and withdrawing a request are {@code customer.manage}, as for an erasure. <strong>Producing the bundle and
 * reading it are {@code customer.pii.export}</strong>, the capability that opens personal data to a person, and they carry
 * a declared purpose that lands on the audit trail and in the merchant's egress log. A correction is {@code
 * customer.manage}, like the edit it wraps, and needs a reference to its evidence.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/customers")
@Tag(
        name = "Customer privacy requests",
        description = "A data-subject copy of her data, corrections against evidence, and the worklist of both")
public class CustomerPrivacyController {

    private final CustomerExportService exports;
    private final CustomerCorrectionService corrections;
    private final JdbcPrivacyRequestStore worklist;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CustomerPrivacyController(
            CustomerExportService exports,
            CustomerCorrectionService corrections,
            JdbcPrivacyRequestStore worklist,
            CurrentActor currentActor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.exports = exports;
        this.corrections = corrections;
        this.worklist = worklist;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private ActorRef staffActor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    // ------------------------------------------------------------------ the worklist

    @GetMapping("/privacy-requests")
    @RequiresCapability(Capability.CUSTOMER_ERASURE_RAISE)
    @Operation(
            summary = "Every data-subject request across the tenant, newest first",
            description =
                    "Erasure requests, export requests and corrections in one list, for Settings 10.11. Filter with "
                            + "kind=ERASURE|EXPORT|CORRECTION and status=PENDING|COMPLETED|CANCELLED, or omit either. A line "
                            + "names the kind, the customer account, where it stands and when; never a value. Acting on a line "
                            + "goes through the per-customer endpoints using the customerAccountId it carries.")
    public ResponseEntity<List<PrivacyRequestResponse>> privacyRequests(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Pattern(regexp = "ERASURE|EXPORT|CORRECTION") String kind,
            @RequestParam(required = false) @Pattern(regexp = "PENDING|COMPLETED|CANCELLED") String status,
            @RequestParam(required = false) Integer limit) {
        int effectiveLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 500));
        return ResponseEntity.ok(worklist.worklist(tenantId, kind, status, effectiveLimit).stream()
                .map(PrivacyRequestResponse::of)
                .toList());
    }

    // ------------------------------------------------------------------ export

    @PostMapping("/{accountId}/export-requests")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Ask for a copy of this customer's data on her behalf",
            description = "Records the request and the date the tenant owes an answer by; it gathers nothing by itself "
                    + "(ADR 0029). Idempotent: a second call while one is PENDING returns that same request.")
    public ResponseEntity<ExportRequestResponse> requestExport(
            @PathVariable UUID tenantId, @PathVariable UUID accountId) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ExportRequestResponse.of(
                            exports.request(tenantId, accountId, RequestedVia.OPERATIONS, staffActor()),
                            objectMapper,
                            clock.instant()));
        } catch (CustomerErasureService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        } catch (CustomerExportService.AccountNotExportableException gone) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, gone.getMessage());
        }
    }

    @GetMapping("/{accountId}/export-requests")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "This customer's export requests, newest first",
            description =
                    "Whether a request is waiting, produced, withdrawn or expired, and what the bundle contains by "
                            + "module and table (counts, never values).")
    public ResponseEntity<List<ExportRequestResponse>> exportRequests(
            @PathVariable UUID tenantId, @PathVariable UUID accountId) {
        try {
            return ResponseEntity.ok(exports.history(tenantId, accountId).stream()
                    .map(row -> ExportRequestResponse.of(row, objectMapper, clock.instant()))
                    .toList());
        } catch (CustomerErasureService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        }
    }

    @PostMapping("/{accountId}/export-requests/{requestId}/produce")
    @RequiresCapability(value = Capability.CUSTOMER_PII_EXPORT, mutating = true)
    @Operation(
            summary = "Gather the customer's data into a bundle",
            description = "Reads her data from every module, decrypted under the purpose you state, into one JSON "
                    + "document that is kept for a limited time (ADR 0029). The purpose is recorded on the audit trail "
                    + "and appears in the merchant's egress log. Under an ADR 0027 approval when the tenant has "
                    + "published a customer.pii.export policy: the first call answers 422 SECOND_APPROVER_REQUIRED "
                    + "naming approvalRequestId, and once a second person has approved it the same call produces the "
                    + "bundle. Idempotent: a request already produced returns itself.")
    public ResponseEntity<ExportRequestResponse> produceExport(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ProduceExportRequest body) {
        try {
            return switch (exports.produce(
                    tenantId, accountId, requestId, body.purpose().strip(), staffActor())) {
                case CustomerExportService.Result.Done done ->
                    ResponseEntity.ok(ExportRequestResponse.of(done.request(), objectMapper, clock.instant()));
                case CustomerExportService.Result.Awaiting awaiting -> throw awaitingApproval(awaiting.outcome());
            };
        } catch (CustomerExportService.NoSuchExportRequestException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such export request");
        } catch (CustomerExportService.ExportRequestNotPendingException notPending) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, notPending.getMessage());
        } catch (CustomerExportService.AccountNotExportableException gone) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, gone.getMessage());
        }
    }

    @GetMapping("/{accountId}/export-requests/{requestId}/bundle")
    @RequiresCapability(Capability.CUSTOMER_PII_EXPORT)
    @Operation(
            summary = "A short-lived link to the produced bundle",
            description =
                    "The link works for a few minutes and is not stored. Each call is an audit fact naming you and "
                            + "your purpose, and appears in the merchant's egress log. 404 when the request has no bundle (not "
                            + "produced, expired, or removed by an erasure).")
    public ResponseEntity<BundleLinkResponse> exportBundle(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @PathVariable UUID requestId,
            @RequestParam @NotBlank @Size(max = 200) String purpose) {
        try {
            CustomerExportService.BundleLink link =
                    exports.bundleLink(tenantId, accountId, requestId, purpose.strip(), staffActor());
            return ResponseEntity.ok(new BundleLinkResponse(link.url().toString(), link.expiresAt()));
        } catch (CustomerExportService.NoSuchExportRequestException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such export request");
        } catch (CustomerExportService.BundleNotAvailableException none) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, none.getMessage());
        }
    }

    @PostMapping("/{accountId}/export-requests/{requestId}/cancel")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Withdraw a PENDING export request",
            description = "Idempotent for a request already withdrawn. Refused for one already produced.")
    public ResponseEntity<ExportRequestResponse> cancelExport(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @PathVariable UUID requestId) {
        try {
            return ResponseEntity.ok(ExportRequestResponse.of(
                    exports.cancel(tenantId, accountId, requestId, staffActor()), objectMapper, clock.instant()));
        } catch (CustomerExportService.NoSuchExportRequestException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such export request");
        } catch (CustomerExportService.ExportRequestNotPendingException produced) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, produced.getMessage());
        }
    }

    // ------------------------------------------------------------------ corrections

    @GetMapping("/{accountId}/corrections")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "The corrections made to this customer's data, newest first",
            description = "What was amended, against which evidence reference, by whom and when. The value that was "
                    + "replaced is kept but is not returned here: it is part of a data-subject export.")
    public ResponseEntity<List<CorrectionResponse>> correctionHistory(
            @PathVariable UUID tenantId, @PathVariable UUID accountId) {
        try {
            return ResponseEntity.ok(corrections.history(tenantId, accountId).stream()
                    .map(CorrectionResponse::of)
                    .toList());
        } catch (CustomerErasureService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        }
    }

    @PutMapping("/{accountId}/corrections/display-name")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Correct the display name against evidence",
            description =
                    "The same edit as the profile PUT, with the previous name kept under retention and the evidence "
                            + "reference recorded. A blank name clears it. Requires If-Match. `applied` is false when the name "
                            + "was already that.")
    public ResponseEntity<CorrectionResultResponse> correctDisplayName(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @Valid @RequestBody CorrectDisplayNameRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return correcting(() -> corrections.correctDisplayName(
                tenantId, accountId, (int) expected, body.displayName(), body.evidenceReference(), staffActor()));
    }

    @PutMapping("/{accountId}/corrections/date-of-birth")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Correct the date of birth against evidence",
            description =
                    "Replaces a wrong date with the right one, keeping the previous date under retention. Clearing "
                            + "a date is not a correction. Requires If-Match.")
    public ResponseEntity<CorrectionResultResponse> correctDateOfBirth(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @Valid @RequestBody CorrectDateOfBirthRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return correcting(() -> corrections.correctDateOfBirth(
                tenantId, accountId, (int) expected, body.dateOfBirth(), body.evidenceReference(), staffActor()));
    }

    @PutMapping("/{accountId}/corrections/contact-points/{contactPointId}")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Correct a contact point's value against evidence",
            description = "The type stays; verification resets because the value changed. The previous value is kept "
                    + "under retention. `applied` is false when the value normalises to what was already there.")
    public ResponseEntity<CorrectionResultResponse> correctContactPoint(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @PathVariable UUID contactPointId,
            @Valid @RequestBody CorrectContactPointRequest body) {
        return correcting(() -> corrections.correctContactPoint(
                tenantId, accountId, contactPointId, body.value(), body.evidenceReference(), staffActor()));
    }

    @PutMapping("/{accountId}/corrections/addresses/{addressId}")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Correct an address against evidence",
            description = "Replaces the whole address, keeping the previous one under retention. Requires If-Match.")
    public ResponseEntity<CorrectionResultResponse> correctAddress(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @PathVariable UUID addressId,
            @Valid @RequestBody CorrectAddressRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return correcting(() -> corrections.correctAddress(
                tenantId,
                accountId,
                addressId,
                (int) expected,
                body.label(),
                body.fields(),
                body.deliveryInstructions(),
                body.latitude(),
                body.longitude(),
                body.coordinateSource(),
                body.evidenceReference(),
                staffActor()));
    }

    private ResponseEntity<CorrectionResultResponse> correcting(java.util.function.Supplier<Outcome> correction) {
        try {
            return switch (correction.get()) {
                case Outcome.Applied applied ->
                    ResponseEntity.ok(new CorrectionResultResponse(true, applied.correctionId(), applied.version()));
                case Outcome.Unchanged unchanged -> ResponseEntity.ok(new CorrectionResultResponse(false, null, null));
            };
        } catch (CustomerProfileService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        } catch (CustomerProfileService.ContactPointNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such contact point");
        } catch (CustomerProfileService.AddressNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such address");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    /** The 422 the producing person is answered with while a second approver has not decided. */
    private static ApiException awaitingApproval(ApprovalOutcome outcome) {
        return switch (outcome) {
            case ApprovalOutcome.Pending pending ->
                new ApiException(
                        ErrorCode.SECOND_APPROVER_REQUIRED,
                        "This export needs a second approver. Approval request %s is pending."
                                .formatted(pending.requestId()),
                        java.util.Map.of(
                                "approvalRequestId", pending.requestId().toString(), "approvalStatus", "PENDING"));
            case ApprovalOutcome.Declined declined ->
                new ApiException(
                        ErrorCode.SECOND_APPROVER_REQUIRED,
                        "Approval request %s was declined, so the data was not exported."
                                .formatted(declined.requestId()),
                        java.util.Map.of(
                                "approvalRequestId", declined.requestId().toString(), "approvalStatus", "DECLINED"));
            default -> new ApiException(ErrorCode.SECOND_APPROVER_REQUIRED, "A second approver is required");
        };
    }

    // ------------------------------------------------------------------ wire shapes

    public record ProduceExportRequest(
            @NotBlank
            @Size(min = 3, max = 200)
            @Schema(
                    description =
                            "Why the data is being revealed, in your words. Recorded on the audit trail, shown to "
                                    + "the second approver and passed to every decryption.")
            String purpose) {}

    /** The cover of a correction request: a reference to the evidence, never the evidence. */
    private static final String EVIDENCE_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,79}$";

    public record CorrectDisplayNameRequest(
            @Size(max = 200) @Nullable String displayName,

            @NotBlank
            @Pattern(regexp = EVIDENCE_PATTERN)
            @Schema(
                    description =
                            "A reference to the evidence (a ticket or document number): letters, digits and . _ : / # -, "
                                    + "3 to 80 characters, no spaces. Not the evidence itself.")
            String evidenceReference) {}

    public record CorrectDateOfBirthRequest(
            @NotBlank @Schema(description = "ISO-8601 date, yyyy-MM-dd")
            String dateOfBirth,

            @NotBlank @Pattern(regexp = EVIDENCE_PATTERN) String evidenceReference) {}

    public record CorrectContactPointRequest(
            @NotBlank @Size(max = 200) String value,

            @NotBlank @Pattern(regexp = EVIDENCE_PATTERN) String evidenceReference) {}

    public record CorrectAddressRequest(
            @NotBlank @Size(max = 64) String label,
            @NotNull @Valid AddressFields fields,
            @Size(max = 500) @Nullable String deliveryInstructions,
            @DecimalMin("-90") @DecimalMax("90") @Nullable Double latitude,
            @DecimalMin("-180") @DecimalMax("180") @Nullable Double longitude,
            @NotNull CoordinateSource coordinateSource,

            @NotBlank @Pattern(regexp = EVIDENCE_PATTERN) String evidenceReference) {}

    public record CorrectionResultResponse(
            boolean applied,
            @Nullable UUID correctionId,

            @Nullable @Schema(description = "The stored version after the write, where the corrected thing has one")
            Integer version) {}

    public record CorrectionResponse(
            UUID id,
            String subject,
            @Nullable UUID targetId,
            String evidenceReference,
            String correctedByActorType,
            String correctedByActorId,
            Instant correctedAt) {

        static CorrectionResponse of(CorrectionRow row) {
            return new CorrectionResponse(
                    row.id(),
                    row.subject(),
                    row.targetId(),
                    row.evidenceReference(),
                    row.correctedByActorType(),
                    row.correctedByActorId(),
                    row.correctedAt());
        }
    }

    public record BundleLinkResponse(String url, Instant expiresAt) {}

    /** What a bundle contains, by module and table: counts, never values. */
    public record ManifestResponse(List<ManifestSection> sections, List<String> withheld) {}

    public record ManifestSection(String name, List<ManifestTable> tables) {}

    public record ManifestTable(String table, int rows, boolean truncated) {}

    /** One export request at whatever point in its life it is asked about. */
    public record ExportRequestResponse(
            UUID id,
            UUID customerAccountId,
            String status,
            String requestedVia,
            Instant requestedAt,

            @Schema(description = "The day the tenant owes the customer her data, fixed when the request was raised")
            Instant responseDueAt,

            @Nullable Instant producedAt,
            @Nullable String producedByActorId,
            @Nullable Instant bundleExpiresAt,

            @Schema(description = "Whether a link can be had right now: produced, not expired, not removed")
            boolean bundleAvailable,

            @Nullable Instant cancelledAt,
            @Nullable ManifestResponse manifest) {

        static ExportRequestResponse of(ExportRequestRow row, ObjectMapper objectMapper, Instant now) {
            ManifestResponse manifest = row.manifestJson() == null
                    ? null
                    : objectMapper.readValue(row.manifestJson(), new TypeReference<ManifestResponse>() {});
            return new ExportRequestResponse(
                    row.id(),
                    row.customerAccountId(),
                    row.status(),
                    row.requestedVia(),
                    row.requestedAt(),
                    row.responseDueAt(),
                    row.producedAt(),
                    row.producedByActorId(),
                    row.bundleExpiresAt(),
                    row.bundleAvailableAt(now),
                    row.cancelledAt(),
                    manifest);
        }
    }

    /** One line of the tenant's data-subject worklist. */
    public record PrivacyRequestResponse(
            String kind,
            UUID id,
            UUID customerAccountId,
            String status,
            String requestedVia,
            Instant requestedAt,
            @Nullable Instant settledAt,
            @Nullable Instant dueAt,

            @Nullable
            @Schema(
                    description =
                            "For a correction: what was amended (DISPLAY_NAME, DATE_OF_BIRTH, CONTACT_POINT, ADDRESS)")
            String subject) {

        static PrivacyRequestResponse of(PrivacyRequestRow row) {
            return new PrivacyRequestResponse(
                    row.kind(),
                    row.id(),
                    row.customerAccountId(),
                    row.status(),
                    row.requestedVia(),
                    row.requestedAt(),
                    row.settledAt(),
                    row.dueAt(),
                    row.subject());
        }
    }
}
