package uz.horecaos.platform.reviews.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;
import uz.horecaos.platform.reviews.application.RecoveryCaseService;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.AttachCommand;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.CaseDetail;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.CaseSummary;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.CompensationLine;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.Listing;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.Scope;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.TimelineEntry;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.TransitionCommand;
import uz.horecaos.platform.reviews.application.StaffReferences.Person;
import uz.horecaos.platform.reviews.domain.CaseEventKind;
import uz.horecaos.platform.reviews.domain.CaseOutcome;
import uz.horecaos.platform.reviews.domain.CaseState;
import uz.horecaos.platform.reviews.domain.CompensationKind;
import uz.horecaos.platform.reviews.domain.SlaState;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.CaseRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.CompensationRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.Counts;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * §5.4 Reviews, the service-recovery worklist (ADR 0159, Decisions 8-11): the cases about low reviews,
 * their owners, their clocks, their timeline, and the compensation an operator linked to each.
 *
 * <p>Two reaches, one worklist. The brand's whole worklist is read at {@code BRAND} scope; every case is
 * <em>worked</em> -- opened, moved, annotated, given a remedy -- at the case's own {@code LOCATION}
 * scope, so a branch manager works her branch's cases and no other's, and a brand manager, whose grant
 * covers every branch, works them all through the same routes. A case outside the caller's reach is
 * {@code RESOURCE_NOT_FOUND}, the same answer as no such case.
 *
 * <p>The drawer is a {@code GET} and is the only place a note or the customer's comment is returned. The
 * comment is withheld, with a marker, from a caller who holds {@code recovery.case.manage} but not {@code
 * review.read} at that location -- the case can still be worked without reading the customer's words.
 * Every mutation answers with ids, states, a version and codes: its response is stored for a day by
 * idempotency, and a note or a comment must not be in it.
 *
 * <p>A case <strong>links to compensation and never pays it</strong>: the operator uses the order-remedy
 * console (ADR 0048) or mints a code (ADR 0044) and attaches the result here.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}")
@Tag(
        name = "Service recovery",
        description = "The worklist of cases about low reviews, with owners, clocks and a timeline")
public class OperationsRecoveryCaseController {

    private final RecoveryCaseService cases;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;

    public OperationsRecoveryCaseController(
            RecoveryCaseService cases, AuthorizationService authorization, CurrentActor currentActor) {
        this.cases = cases;
        this.authorization = authorization;
        this.currentActor = currentActor;
    }

    // ===================================================================== the brand's worklist

    @GetMapping("/recovery-cases")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's service-recovery cases",
            description = "Open cases most-urgent first (by resolution due time), closed ones most "
                    + "recently closed first. Filter by state, owner, branch and where the clocks "
                    + "stand (ON_TRACK, AT_RISK, BREACHED, or MET for a closed case in time). "
                    + "Cursor-paginated per ADR 0031. No comment and no note: they are in the drawer.")
    public Page<CaseSummaryResponse> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable CaseState state,
            @RequestParam(required = false) @Nullable UUID ownerStaffMemberId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @RequestParam(required = false) @Nullable SlaState slaState,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        Listing listing = cases.list(
                new Scope(tenantId, brandId, locationId), state, ownerStaffMemberId, slaState, cursor, pageSize);
        return new Page<>(listing.items().stream().map(CaseSummaryResponse::of).toList(), listing.nextCursor());
    }

    @GetMapping("/recovery-cases/summary")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "Counts of the brand's cases by state and by where their clocks stand",
            description = "Optionally narrowed to one branch. Computed from the same clock arithmetic "
                    + "the list uses, as of now.")
    public CountsResponse summary(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {
        return CountsResponse.of(cases.counts(new Scope(tenantId, brandId, locationId)));
    }

    // =================================================================== one branch's cases

    @GetMapping("/locations/{locationId}/recovery-cases")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "One branch's service-recovery cases",
            description = "As the brand worklist, for a branch the caller works: only this branch's cases.")
    public Page<CaseSummaryResponse> listForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable CaseState state,
            @RequestParam(required = false) @Nullable UUID ownerStaffMemberId,
            @RequestParam(required = false) @Nullable SlaState slaState,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        Listing listing = cases.list(
                Scope.location(tenantId, brandId, locationId), state, ownerStaffMemberId, slaState, cursor, pageSize);
        return new Page<>(listing.items().stream().map(CaseSummaryResponse::of).toList(), listing.nextCursor());
    }

    @GetMapping("/locations/{locationId}/recovery-cases/summary")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(summary = "Counts of one branch's cases by state and by where their clocks stand")
    public CountsResponse summaryForLocation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return CountsResponse.of(cases.counts(Scope.location(tenantId, brandId, locationId)));
    }

    @GetMapping("/locations/{locationId}/recovery-cases/{caseId}")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "One case: the drawer",
            description = "The rating, tags, order, timeline (with each note) and the linked "
                    + "compensation. The customer's comment is returned only if the caller also holds "
                    + "review.read at this location; otherwise commentWithheld is true and the comment is "
                    + "null, and the case can still be worked. The ETag is the version to send back as "
                    + "If-Match. A case outside this branch is RESOURCE_NOT_FOUND.")
    public ResponseEntity<CaseDetailResponse> detail(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID caseId) {
        boolean mayReadComment = authorization.has(
                currentActor.get().subject(),
                Capability.REVIEW_READ,
                ResourceScope.location(tenantId, brandId, locationId));
        CaseDetail detail = cases.detail(Scope.location(tenantId, brandId, locationId), caseId, mayReadComment);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(detail.summary().row().version()))
                .body(CaseDetailResponse.of(detail));
    }

    @PostMapping("/locations/{locationId}/recovery-cases")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Open a case from a review, by hand",
            description = "For a review the recovery policy did not flag. One case per review: a second "
                    + "is RESOURCE_CONFLICT. UNPROCESSABLE_STATE while service recovery is switched off "
                    + "for the tenant. The review must belong to this branch.")
    public ResponseEntity<CaseMutationResponse> open(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody OpenCaseRequest body) {
        CaseRow opened = cases.openManually(Scope.location(tenantId, brandId, locationId), body.reviewId(), actor());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(opened.version()))
                .body(CaseMutationResponse.of(opened));
    }

    @PostMapping("/locations/{locationId}/recovery-cases/{caseId}/transitions")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Move a case along NEW, IN_PROGRESS, RESOLVED and CLOSED_UNRESOLVED",
            description = "Needs If-Match (or expectedVersion) with the case's version. "
                    + "NEW to IN_PROGRESS starts work: the caller becomes the owner unless "
                    + "ownerStaffMemberId names someone else, and it is the first response. "
                    + "IN_PROGRESS to NEW releases it. IN_PROGRESS to IN_PROGRESS with a new "
                    + "ownerStaffMemberId hands it over. Closing needs an outcomeCode from the closed list "
                    + "for the target state; REMEDY_GRANTED needs at least one attached compensation; "
                    + "NEW to CLOSED_UNRESOLVED takes NOT_ACTIONABLE only and dismisses the case "
                    + "without answering it. A terminal state is final. An edge the machine does not "
                    + "have is UNPROCESSABLE_STATE; a lost race is STALE_VERSION.")
    public ResponseEntity<CaseMutationResponse> transition(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID caseId,
            @Valid @RequestBody TransitionRequest body,
            HttpServletRequest request) {
        int expected = ExpectedVersions.resolve(request, body.expectedVersion());
        CaseRow moved = cases.transition(
                Scope.location(tenantId, brandId, locationId),
                caseId,
                new TransitionCommand(body.to(), body.ownerStaffMemberId(), body.outcomeCode(), expected),
                actor());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(moved.version()))
                .body(CaseMutationResponse.of(moved));
    }

    @PostMapping("/locations/{locationId}/recovery-cases/{caseId}/notes")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Add a note to a case's timeline",
            description = "Free text, so it is stored encrypted and returned only by the drawer: this "
                    + "response carries the new timeline entry's id and never the note. Allowed in any "
                    + "state.")
    public ResponseEntity<NoteResponse> addNote(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID caseId,
            @Valid @RequestBody NoteRequest body) {
        UUID entryId = cases.addNote(Scope.location(tenantId, brandId, locationId), caseId, body.note(), actor());
        return ResponseEntity.status(HttpStatus.CREATED).body(new NoteResponse(caseId, entryId));
    }

    @PostMapping("/locations/{locationId}/recovery-cases/{caseId}/compensations")
    @RequiresCapability(value = Capability.RECOVERY_CASE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Link a remedy or an apology code to an open case",
            description = "The case links and never pays: the remedy was recorded through the "
                    + "order-remedy console (kind ORDER_REMEDY, refId its id, which must be a remedy of "
                    + "this case's own order) or the code minted through the benefit-grant mint (kind "
                    + "BENEFIT_GRANT, refId the grant's id, which must belong to this customer and "
                    + "brand). A remedy's amount and currency are copied onto the link now. "
                    + "Another tenant's, another order's and another customer's all answer "
                    + "RESOURCE_NOT_FOUND. A compensation belongs to at most one case "
                    + "(RESOURCE_CONFLICT). A closed case is final (UNPROCESSABLE_STATE).")
    public ResponseEntity<CompensationResponse> attach(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID caseId,
            @Valid @RequestBody AttachRequest body) {
        CompensationRow link = cases.attach(
                Scope.location(tenantId, brandId, locationId),
                caseId,
                new AttachCommand(body.kind(), body.refId()),
                actor());
        return ResponseEntity.status(HttpStatus.CREATED).body(CompensationResponse.of(link));
    }

    private String actor() {
        return currentActor.get().subject();
    }

    // ------------------------------------------------------------- wire shapes

    public record OpenCaseRequest(@NotNull UUID reviewId) {}

    /**
     * @param ownerStaffMemberId boxed and optional: the caller by default
     * @param outcomeCode        required to close, absent otherwise
     * @param expectedVersion    the case's version, if not sent as If-Match
     */
    public record TransitionRequest(
            @NotNull CaseState to,
            @Nullable UUID ownerStaffMemberId,
            @Nullable CaseOutcome outcomeCode,
            @Nullable Integer expectedVersion) {}

    public record NoteRequest(
            @NotNull @Size(min = 1, max = RecoveryCaseService.MAXIMUM_NOTE_LENGTH)
            String note) {}

    public record AttachRequest(
            @NotNull CompensationKind kind, @NotNull UUID refId) {}

    /** A staff member as the board shows them: the name when there is one, else the non-personal reference. */
    public record StaffResponse(UUID id, String displayReference, String name) {

        static @Nullable StaffResponse of(@Nullable Person person) {
            return person == null
                    ? null
                    : new StaffResponse(person.memberId(), person.displayReference(), person.label());
        }
    }

    public record CaseSummaryResponse(
            UUID id,
            CaseState state,
            @Nullable CaseOutcome outcomeCode,
            String source,
            UUID reviewId,
            UUID orderId,
            @Nullable String orderNumber,
            UUID locationId,
            UUID customerAccountId,
            int rating,
            SlaState slaState,
            @Nullable StaffResponse owner,
            Instant createdAt,
            Instant firstResponseDueAt,
            Instant resolutionDueAt,
            @Nullable Instant firstResponseAt,
            @Nullable Instant closedAt,
            boolean firstResponseBreached,
            boolean resolutionBreached,
            int compensationCount,
            int version) {

        static CaseSummaryResponse of(CaseSummary summary) {
            CaseRow row = summary.row();
            return new CaseSummaryResponse(
                    row.id(),
                    row.state(),
                    row.outcome(),
                    row.source(),
                    row.reviewId(),
                    row.orderId(),
                    summary.orderNumber(),
                    row.locationId(),
                    row.customerAccountId(),
                    summary.rating(),
                    summary.slaState(),
                    StaffResponse.of(summary.owner()),
                    row.createdAt(),
                    row.firstResponseDueAt(),
                    row.resolutionDueAt(),
                    row.firstResponseAt(),
                    row.closedAt(),
                    row.firstResponseBreachedAt() != null,
                    row.resolutionBreachedAt() != null,
                    summary.compensationCount(),
                    row.version());
        }
    }

    public record CountsResponse(Map<CaseState, Long> byState, Map<SlaState, Long> bySla) {

        static CountsResponse of(Counts counts) {
            return new CountsResponse(counts.byState(), counts.bySla());
        }
    }

    public record TagResponse(
            UUID id,
            String code,
            String category,
            String sentiment,
            Map<String, String> labels,
            @Nullable UUID iconAssetId) {}

    public record OrderResponse(UUID id, String publicOrderNumber, String status, String currency, long totalMinor) {

        static @Nullable OrderResponse of(@Nullable OrderSummary order) {
            return order == null
                    ? null
                    : new OrderResponse(
                            order.orderId(),
                            order.publicOrderNumber(),
                            order.status(),
                            order.currency(),
                            order.totalMinor());
        }
    }

    /** One line of the timeline; {@code detailCode} is an outcome, a kind or a staff member's non-personal reference. */
    public record TimelineResponse(
            UUID id,
            CaseEventKind kind,
            @Nullable CaseState fromState,
            @Nullable CaseState toState,
            @Nullable String detailCode,
            String actorType,
            @Nullable StaffResponse actor,
            @Nullable String note,
            Instant occurredAt) {

        static TimelineResponse of(TimelineEntry entry) {
            return new TimelineResponse(
                    entry.id(),
                    entry.kind(),
                    entry.fromState(),
                    entry.toState(),
                    entry.detailCode(),
                    entry.actorType(),
                    StaffResponse.of(entry.actor()),
                    entry.note(),
                    entry.occurredAt());
        }
    }

    public record CompensationResponse(
            UUID caseId,
            CompensationKind kind,
            UUID refId,
            UUID orderId,
            @Nullable Long amountMinor,
            @Nullable String currency,
            Instant attachedAt,
            @Nullable StaffResponse attachedBy) {

        static CompensationResponse of(CompensationRow row) {
            return new CompensationResponse(
                    row.caseId(),
                    row.kind(),
                    row.refId(),
                    row.orderId(),
                    row.amountMinor(),
                    row.currency(),
                    row.attachedAt(),
                    null);
        }

        static CompensationResponse of(CompensationLine line) {
            CompensationRow row = line.row();
            return new CompensationResponse(
                    row.caseId(),
                    row.kind(),
                    row.refId(),
                    row.orderId(),
                    row.amountMinor(),
                    row.currency(),
                    row.attachedAt(),
                    StaffResponse.of(line.attachedBy()));
        }
    }

    /**
     * The drawer.
     *
     * @param hasComment      whether the review has a comment at all
     * @param comment         null unless the caller also holds review.read at this location
     * @param commentWithheld the review has a comment and the caller may not read it
     */
    public record CaseDetailResponse(
            CaseSummaryResponse summary,
            List<TagResponse> tags,
            boolean hasComment,
            @Nullable String comment,
            boolean commentWithheld,
            @Nullable OrderResponse order,
            List<TimelineResponse> timeline,
            List<CompensationResponse> compensations) {

        static CaseDetailResponse of(CaseDetail detail) {
            return new CaseDetailResponse(
                    CaseSummaryResponse.of(detail.summary()),
                    detail.tags().stream()
                            .map(tag -> new TagResponse(
                                    tag.row().id(),
                                    tag.row().code(),
                                    tag.row().category().name(),
                                    tag.row().sentiment().name(),
                                    tag.labels(),
                                    tag.row().iconAssetId()))
                            .toList(),
                    detail.hasComment(),
                    detail.comment(),
                    detail.commentWithheld(),
                    OrderResponse.of(detail.order()),
                    detail.timeline().stream().map(TimelineResponse::of).toList(),
                    detail.compensations().stream()
                            .map(CompensationResponse::of)
                            .toList());
        }
    }

    /** What a mutation answers with: ids, states, a version and codes -- never a note or the comment. */
    public record CaseMutationResponse(
            UUID id,
            CaseState state,
            @Nullable CaseOutcome outcomeCode,
            @Nullable UUID ownerStaffMemberId,
            int version) {

        static CaseMutationResponse of(CaseRow row) {
            return new CaseMutationResponse(
                    row.id(), row.state(), row.outcome(), row.ownerStaffMemberId(), row.version());
        }
    }

    public record NoteResponse(UUID caseId, UUID entryId) {}
}
