package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;
import uz.horecaos.platform.pricing.api.BenefitGrantDirectory;
import uz.horecaos.platform.reviews.api.RecoveryCaseOpened;
import uz.horecaos.platform.reviews.api.RecoveryCaseSlaBreached;
import uz.horecaos.platform.reviews.api.RecoveryCaseStateChanged;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagView;
import uz.horecaos.platform.reviews.application.RecoveryPolicyService.InForce;
import uz.horecaos.platform.reviews.application.StaffReferences.Person;
import uz.horecaos.platform.reviews.domain.CaseEventKind;
import uz.horecaos.platform.reviews.domain.CaseOutcome;
import uz.horecaos.platform.reviews.domain.CaseSource;
import uz.horecaos.platform.reviews.domain.CaseState;
import uz.horecaos.platform.reviews.domain.CompensationKind;
import uz.horecaos.platform.reviews.domain.RevealPurpose;
import uz.horecaos.platform.reviews.domain.SlaState;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.Breach;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.CaseRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.CompensationRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.Counts;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.EventRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.Listed;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Row;
import uz.horecaos.platform.reviews.spi.CompensationDirectory;
import uz.horecaos.platform.reviews.spi.CompensationDirectory.CompensationSummary;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The service-recovery worklist (ADR 0159, Decisions 8-11): a case about exactly one review, with an
 * owner, two clocks, an outcome from a closed list and an append-only timeline.
 *
 * <p>A case <strong>links to compensation and never pays it</strong>. The operator refunds or reimburses
 * through ADR 0048's order-remedy console, or mints an apology code through ADR 0044's benefit-grant
 * mint, each under its own approval threshold; the case records the link, verified to belong to the same
 * order (a remedy) or the same customer and brand (a grant), and copies a remedy's amount and currency
 * at that moment so this module never reads {@code payments} again. No approval machinery is added here:
 * {@code recovery.remedy.approve} stays an unused constant.
 *
 * <p><strong>Personal data.</strong> A note is free text and therefore {@code PERSONAL}: it is encrypted
 * by {@link ReviewCommentVault}, returned only by {@link #detail} (a GET), and carried by no mutation's
 * response, no event, no log line and no audit change document. The owner is stored as a staff member id
 * and resolved to a name only when read; an event carries the non-personal {@code display_reference}.
 *
 * <p>Every method that takes a {@link Scope} treats a case outside it as absent -- {@code
 * RESOURCE_NOT_FOUND}, the same answer as "no such case" -- so a location manager of one branch cannot
 * learn that another branch's case exists.
 */
@Service
public class RecoveryCaseService {

    public static final int MAXIMUM_NOTE_LENGTH = 2000;

    public static final String AUDIT_OPEN = "reviews.case.open";
    public static final String AUDIT_TRANSITION = "reviews.case.transition";
    public static final String AUDIT_ASSIGN = "reviews.case.assign";
    public static final String AUDIT_COMPENSATE = "reviews.case.compensate";

    private static final ActorRef INTAKE = ActorRef.systemJob("reviews.recovery-intake");

    private final JdbcRecoveryCaseStore cases;
    private final JdbcReviewStore reviews;
    private final ReviewCommentVault vault;
    private final ReviewsSettings settings;
    private final RecoveryPolicyService policy;
    private final StaffReferences staff;
    private final FeedbackTagService tags;
    private final OrderDirectory orders;
    private final BenefitGrantDirectory grants;
    private final List<CompensationDirectory> compensationDirectories;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final ReviewsMetrics metrics;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RecoveryCaseService(
            JdbcRecoveryCaseStore cases,
            JdbcReviewStore reviews,
            ReviewCommentVault vault,
            ReviewsSettings settings,
            RecoveryPolicyService policy,
            StaffReferences staff,
            FeedbackTagService tags,
            OrderDirectory orders,
            BenefitGrantDirectory grants,
            List<CompensationDirectory> compensationDirectories,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            ReviewsMetrics metrics,
            Clock clock) {
        this.cases = cases;
        this.reviews = reviews;
        this.vault = vault;
        this.settings = settings;
        this.policy = policy;
        this.staff = staff;
        this.tags = tags;
        this.orders = orders;
        this.grants = grants;
        this.compensationDirectories = compensationDirectories;
        this.audit = audit;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- scope

    /**
     * Where a caller works: a brand, or one branch of it. A branch scope sees that branch's cases and
     * nothing else; a brand scope sees them all.
     */
    public record Scope(
            UUID tenantId, UUID brandId, @Nullable UUID locationId) {

        public static Scope brand(UUID tenantId, UUID brandId) {
            return new Scope(tenantId, brandId, null);
        }

        public static Scope location(UUID tenantId, UUID brandId, UUID locationId) {
            return new Scope(tenantId, brandId, locationId);
        }

        boolean covers(CaseRow row) {
            return row.tenantId().equals(tenantId)
                    && row.brandId().equals(brandId)
                    && (locationId == null || row.locationId().equals(locationId));
        }
    }

    // ------------------------------------------------------------------ intake

    /**
     * Opens a case for a review that has just been written, in the review's own transaction, when
     * {@code feature.service_recovery} is on and the rating is at or under the policy's threshold.
     *
     * @return the case, or empty when the flag is off or the rating is above the threshold
     */
    @Transactional
    public Optional<CaseRow> openAutomatically(Row review) {
        if (!settings.serviceRecoveryOn(review.tenantId())) {
            return Optional.empty();
        }
        InForce inForce = policy.resolveAt(review.tenantId(), review.brandId());
        if (!inForce.document().opensFor(review.rating())) {
            return Optional.empty();
        }
        return Optional.of(open(review, CaseSource.AUTO, null, INTAKE, inForce));
    }

    /**
     * Opens a case by hand from any review of this branch. The first thing a person does with a review a
     * policy did not flag.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for a review outside the scope,
     *                      {@code UNPROCESSABLE_STATE} when the feature is off,
     *                      {@code RESOURCE_CONFLICT} when the review already has a case
     */
    @Transactional
    public CaseRow openManually(Scope scope, UUID reviewId, String actorSubject) {
        Row review = reviews.findById(scope.tenantId(), reviewId)
                .filter(found -> found.brandId().equals(scope.brandId())
                        && (scope.locationId() == null || found.locationId().equals(scope.locationId())))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such review"));
        if (!settings.serviceRecoveryOn(scope.tenantId())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Service recovery is not switched on for this tenant");
        }
        if (cases.findByReview(scope.tenantId(), reviewId).isPresent()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This review already has a case");
        }
        InForce inForce = policy.resolveAt(scope.tenantId(), scope.brandId());
        UUID openedBy = staff.memberOf(scope.tenantId(), actorSubject).orElse(null);
        return open(review, CaseSource.MANUAL, openedBy, ActorRef.user(actorSubject, null), inForce);
    }

    private CaseRow open(Row review, CaseSource source, @Nullable UUID openedBy, ActorRef actor, InForce inForce) {
        Instant now = clock.instant();
        CaseRow row = new CaseRow(
                Ids.newId(),
                review.tenantId(),
                review.brandId(),
                review.locationId(),
                review.orderId(),
                review.id(),
                review.customerAccountId(),
                source.name(),
                CaseState.NEW,
                null,
                null,
                openedBy,
                inForce.version(),
                inForce.document().firstResponseDueAt(now),
                inForce.document().resolutionDueAt(now),
                null,
                null,
                null,
                null,
                1,
                now,
                now);
        try {
            cases.insert(row);
        } catch (DuplicateKeyException raced) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This review already has a case");
        }
        cases.insertEvent(new EventRow(
                Ids.newId(),
                row.tenantId(),
                row.id(),
                CaseEventKind.OPENED,
                null,
                CaseState.NEW,
                source.name(),
                openedBy == null ? "SYSTEM" : "STAFF",
                openedBy,
                null,
                now));

        audit.record(AuditFact.of(AUDIT_OPEN, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(row.tenantId(), row.brandId(), row.locationId()))
                .target("RecoveryCase", row.id())
                .targetVersion(1L)
                .because(source == CaseSource.AUTO ? "Opened by the recovery policy" : "Opened by hand from a review")
                .usingCapability(Capability.RECOVERY_CASE_MANAGE.code())
                .changed(ChangeDocuments.created(Map.of(
                        "state", CaseState.NEW.name(),
                        "source", source.name(),
                        "policyVersion", inForce.version())))
                .correlatedBy(row.id().toString())
                .occurredAt(now)
                .build());
        events.publishEvent(new RecoveryCaseOpened(
                Ids.newId(),
                row.tenantId(),
                row.id(),
                row.reviewId(),
                row.orderId(),
                row.brandId(),
                row.locationId(),
                source.name(),
                review.rating(),
                row.firstResponseDueAt(),
                row.resolutionDueAt(),
                now));
        metrics.caseEnteredState(CaseState.NEW.name());
        return row;
    }

    // -------------------------------------------------------------------- work

    /** A move the operator asked for. */
    public record TransitionCommand(
            CaseState to,
            @Nullable UUID ownerStaffMemberId,
            @Nullable CaseOutcome outcome,
            int expectedVersion) {}

    /**
     * Moves a case along the four-state machine, or hands an in-progress case to another owner.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} outside the scope, {@code STALE_VERSION} on a lost
     *                      race, {@code UNPROCESSABLE_STATE} for an edge the machine does not have,
     *                      {@code VALIDATION_FAILED} for a missing owner or an outcome that does not fit
     */
    @Transactional
    public CaseRow transition(Scope scope, UUID caseId, TransitionCommand command, String actorSubject) {
        CaseRow current = require(scope, caseId);
        if (current.version() != command.expectedVersion()) {
            throw ApiException.staleVersion(command.expectedVersion(), current.version());
        }
        UUID tenantId = current.tenantId();
        UUID actorMember = staff.memberOf(tenantId, actorSubject).orElse(null);
        CaseState from = current.state();
        CaseState to = command.to();
        Instant now = clock.instant();

        if (from == CaseState.IN_PROGRESS && to == CaseState.IN_PROGRESS) {
            return reassign(current, command, actorMember, actorSubject, now);
        }
        if (!from.targets().contains(to)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "A case cannot move from %s to %s".formatted(from, to));
        }

        return switch (to) {
            case IN_PROGRESS -> start(current, command, actorMember, actorSubject, now);
            case NEW -> release(current, command, actorMember, actorSubject, now);
            case RESOLVED, CLOSED_UNRESOLVED -> close(current, command, actorMember, actorSubject, now);
        };
    }

    private CaseRow start(
            CaseRow current, TransitionCommand command, @Nullable UUID actorMember, String actorSubject, Instant now) {
        UUID ownerId = command.ownerStaffMemberId() != null ? command.ownerStaffMemberId() : actorMember;
        if (ownerId == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Name the owner: the caller has no staff record of their own to become it");
        }
        Person owner = requireActiveStaff(current.tenantId(), ownerId);
        int version = cases.transition(
                        current.tenantId(),
                        current.id(),
                        current.version(),
                        CaseState.NEW,
                        CaseState.IN_PROGRESS,
                        null,
                        ownerId,
                        now,
                        null,
                        now)
                .orElseThrow(() -> stale(current));
        cases.insertEvent(event(
                current,
                CaseEventKind.STARTED,
                CaseState.NEW,
                CaseState.IN_PROGRESS,
                owner.displayReference(),
                actorMember,
                null,
                now));
        return after(current, CaseState.NEW, CaseState.IN_PROGRESS, null, owner, version, actorSubject, now);
    }

    private CaseRow release(
            CaseRow current, TransitionCommand command, @Nullable UUID actorMember, String actorSubject, Instant now) {
        Person previous = current.ownerStaffMemberId() == null
                ? null
                : staff.person(current.tenantId(), current.ownerStaffMemberId()).orElse(null);
        int version = cases.transition(
                        current.tenantId(),
                        current.id(),
                        current.version(),
                        CaseState.IN_PROGRESS,
                        CaseState.NEW,
                        null,
                        null,
                        null,
                        null,
                        now)
                .orElseThrow(() -> stale(current));
        cases.insertEvent(event(
                current,
                CaseEventKind.RELEASED,
                CaseState.IN_PROGRESS,
                CaseState.NEW,
                previous == null ? null : previous.displayReference(),
                actorMember,
                null,
                now));
        return after(current, CaseState.IN_PROGRESS, CaseState.NEW, null, null, version, actorSubject, now);
    }

    private CaseRow close(
            CaseRow current, TransitionCommand command, @Nullable UUID actorMember, String actorSubject, Instant now) {
        CaseState from = current.state();
        CaseState to = command.to();
        CaseOutcome outcome = command.outcome();
        if (outcome == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Closing a case needs an outcome");
        }
        if (!from.mayCloseWith(to, outcome)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The outcome %s does not fit moving a case from %s to %s".formatted(outcome, from, to));
        }
        if (outcome.needsCompensation() && !cases.hasCompensation(current.tenantId(), current.id())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "REMEDY_GRANTED needs at least one attached compensation: attach the remedy or the code first");
        }
        int version = cases.transition(
                        current.tenantId(),
                        current.id(),
                        current.version(),
                        from,
                        to,
                        outcome,
                        current.ownerStaffMemberId(),
                        null,
                        now,
                        now)
                .orElseThrow(() -> stale(current));
        cases.insertEvent(event(
                current,
                to == CaseState.RESOLVED ? CaseEventKind.RESOLVED : CaseEventKind.CLOSED_UNRESOLVED,
                from,
                to,
                outcome.name(),
                actorMember,
                null,
                now));
        Person owner = current.ownerStaffMemberId() == null
                ? null
                : staff.person(current.tenantId(), current.ownerStaffMemberId()).orElse(null);
        return after(current, from, to, outcome, owner, version, actorSubject, now);
    }

    private CaseRow reassign(
            CaseRow current, TransitionCommand command, @Nullable UUID actorMember, String actorSubject, Instant now) {
        UUID ownerId = command.ownerStaffMemberId();
        if (ownerId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Name the new owner");
        }
        if (ownerId.equals(current.ownerStaffMemberId())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "That staff member already owns this case");
        }
        Person owner = requireActiveStaff(current.tenantId(), ownerId);
        Person previous = current.ownerStaffMemberId() == null
                ? null
                : staff.person(current.tenantId(), current.ownerStaffMemberId()).orElse(null);
        int version = cases.reassign(current.tenantId(), current.id(), current.version(), ownerId, now)
                .orElseThrow(() -> stale(current));
        cases.insertEvent(event(
                current,
                CaseEventKind.ASSIGNED,
                CaseState.IN_PROGRESS,
                CaseState.IN_PROGRESS,
                owner.displayReference(),
                actorMember,
                null,
                now));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("owner", previous == null ? null : previous.displayReference());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("owner", owner.displayReference());
        audit.record(AuditFact.of(AUDIT_ASSIGN, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(current.tenantId(), current.brandId(), current.locationId()))
                .target("RecoveryCase", current.id())
                .targetVersion((long) version)
                .because("Handed the case to another owner")
                .usingCapability(Capability.RECOVERY_CASE_MANAGE.code())
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(current.id().toString())
                .occurredAt(now)
                .build());
        return cases.find(current.tenantId(), current.id()).orElseThrow();
    }

    /** The shared tail of a state change: the audit fact, the event, the counter, and the fresh row. */
    private CaseRow after(
            CaseRow current,
            CaseState from,
            CaseState to,
            @Nullable CaseOutcome outcome,
            @Nullable Person owner,
            int version,
            String actorSubject,
            Instant now) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", from.name());
        before.put("outcomeCode", null);
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("state", to.name());
        afterDoc.put("outcomeCode", outcome == null ? null : outcome.name());
        if (owner != null) {
            afterDoc.put("owner", owner.displayReference());
        }
        audit.record(AuditFact.of(AUDIT_TRANSITION, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(current.tenantId(), current.brandId(), current.locationId()))
                .target("RecoveryCase", current.id())
                .targetVersion((long) version)
                .because(reasonFor(to, outcome))
                .usingCapability(Capability.RECOVERY_CASE_MANAGE.code())
                .changed(ChangeDocuments.diff(before, afterDoc))
                .correlatedBy(current.id().toString())
                .occurredAt(now)
                .build());
        events.publishEvent(new RecoveryCaseStateChanged(
                Ids.newId(),
                current.tenantId(),
                current.id(),
                current.orderId(),
                current.brandId(),
                current.locationId(),
                from.name(),
                to.name(),
                outcome == null ? null : outcome.name(),
                owner == null ? null : owner.displayReference(),
                now));
        metrics.caseEnteredState(to.name());
        return cases.find(current.tenantId(), current.id()).orElseThrow();
    }

    /** The audit reason: a code's label, never typed text. */
    private static String reasonFor(CaseState to, @Nullable CaseOutcome outcome) {
        return switch (to) {
            case IN_PROGRESS -> "Started work on the case";
            case NEW -> "Released the case";
            case RESOLVED -> "Resolved the case: " + outcome;
            case CLOSED_UNRESOLVED -> "Closed the case without a remedy: " + outcome;
        };
    }

    /**
     * Adds a note to the timeline. Allowed in any state -- a closed case can still be annotated, because a
     * note changes nothing about what the case decided -- and encrypted before it is stored.
     *
     * @return the new timeline entry's id; never the note
     */
    @Transactional
    public UUID addNote(Scope scope, UUID caseId, String note, String actorSubject) {
        CaseRow current = require(scope, caseId);
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A note cannot be empty");
        }
        if (trimmed.length() > MAXIMUM_NOTE_LENGTH) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A note is at most %d characters".formatted(MAXIMUM_NOTE_LENGTH));
        }
        UUID eventId = Ids.newId();
        UUID actorMember = staff.memberOf(current.tenantId(), actorSubject).orElse(null);
        cases.insertEvent(new EventRow(
                eventId,
                current.tenantId(),
                current.id(),
                CaseEventKind.NOTE,
                null,
                null,
                null,
                "STAFF",
                actorMember,
                vault.protectNote(current.tenantId(), eventId, trimmed),
                clock.instant()));
        return eventId;
    }

    /** What an operator links to a case. */
    public record AttachCommand(CompensationKind kind, UUID refId) {}

    /**
     * Links a remedy or an apology code to an open case.
     *
     * <p>A remedy is verified through {@code reviews.spi.CompensationDirectory} to be this tenant's and to
     * belong to the case's own order, and its amount and currency are copied now; a grant is verified
     * through {@code pricing.api.BenefitGrantDirectory} to be this customer's and this brand's. Another
     * tenant's, another order's and another customer's all answer {@code RESOURCE_NOT_FOUND}, one answer.
     *
     * @throws ApiException {@code RESOURCE_CONFLICT} when the compensation already belongs to a case
     */
    @Transactional
    public CompensationRow attach(Scope scope, UUID caseId, AttachCommand command, String actorSubject) {
        CaseRow current = require(scope, caseId);
        if (current.state().terminal()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "A closed case is final: compensation cannot be attached to it");
        }
        Instant now = clock.instant();
        UUID actorMember = staff.memberOf(current.tenantId(), actorSubject).orElse(null);

        CompensationRow link =
                switch (command.kind()) {
                    case ORDER_REMEDY -> remedyLink(current, command.refId(), actorMember, now);
                    case BENEFIT_GRANT -> grantLink(current, command.refId(), actorMember, now);
                };
        try {
            cases.insertCompensation(link);
        } catch (DuplicateKeyException already) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That compensation is already attached to a case");
        }
        cases.insertEvent(event(
                current,
                CaseEventKind.COMPENSATION_ATTACHED,
                null,
                null,
                command.kind().name(),
                actorMember,
                null,
                now));

        Map<String, Object> attached = new LinkedHashMap<>();
        attached.put("kind", command.kind().name());
        attached.put("refId", command.refId().toString());
        attached.put("amountMinor", link.amountMinor());
        attached.put("currency", link.currency());
        audit.record(AuditFact.of(AUDIT_COMPENSATE, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(current.tenantId(), current.brandId(), current.locationId()))
                .target("RecoveryCase", current.id())
                .because("Linked compensation to the case")
                .usingCapability(Capability.RECOVERY_CASE_MANAGE.code())
                .changed(ChangeDocuments.created(attached))
                .correlatedBy(current.id().toString())
                .occurredAt(now)
                .build());
        return link;
    }

    /**
     * What the drawer's picker may offer (ADR 0159, Decision 9): the remedies recorded against the case's order
     * and the one-off codes minted for the case's customer at its brand, each marked when it already belongs to a
     * case.
     *
     * <p>Read through the compensation SPI and the grant directory under the case's own capability, because the
     * order-remedy console's read needs a tenant-scope payment capability that a branch case worker does not
     * hold. It lists and verifies nothing the attach would not verify again: choosing one still goes through
     * {@link #attach}, which re-checks the tenant, the order and the customer. A case never pays: this is a menu
     * of what exists.
     */
    @Transactional(readOnly = true)
    public CompensationCandidates candidates(Scope scope, UUID caseId) {
        CaseRow current = require(scope, caseId);
        List<CompensationDirectory.CompensationCandidate> remedies = compensationDirectories.stream()
                .filter(directory -> CompensationKind.ORDER_REMEDY.name().equals(directory.kind()))
                .flatMap(directory -> directory.candidatesForOrder(current.tenantId(), current.orderId()).stream())
                .toList();
        Set<UUID> takenRemedies = cases.linkedRefs(
                current.tenantId(),
                CompensationKind.ORDER_REMEDY,
                remedies.stream()
                        .map(CompensationDirectory.CompensationCandidate::refId)
                        .toList());
        List<BenefitGrantDirectory.GrantListing> offered = current.customerAccountId() == null
                ? List.of()
                : grants.forCustomer(current.tenantId(), current.brandId(), current.customerAccountId()).stream()
                        .filter(grant -> !"REVOKED".equals(grant.status()))
                        .toList();
        Set<UUID> takenGrants = cases.linkedRefs(
                current.tenantId(),
                CompensationKind.BENEFIT_GRANT,
                offered.stream()
                        .map(BenefitGrantDirectory.GrantListing::grantId)
                        .toList());
        return new CompensationCandidates(
                remedies.stream()
                        .map(candidate -> new RemedyCandidate(candidate, takenRemedies.contains(candidate.refId())))
                        .toList(),
                offered.stream()
                        .map(listing -> new GrantCandidate(listing, takenGrants.contains(listing.grantId())))
                        .toList(),
                current.orderId(),
                current.customerAccountId());
    }

    /** A remedy on the case's order, and whether it already belongs to a case. */
    public record RemedyCandidate(CompensationDirectory.CompensationCandidate remedy, boolean taken) {}

    /** A one-off code minted for the case's customer, and whether it already belongs to a case. */
    public record GrantCandidate(BenefitGrantDirectory.GrantListing grant, boolean taken) {}

    /**
     * @param orderId           the case's order, so the console can deep-link to the order-remedy console with it
     * @param customerAccountId the case's customer, so the console can prefill the mint; null for a guest
     */
    public record CompensationCandidates(
            List<RemedyCandidate> remedies,
            List<GrantCandidate> grants,
            UUID orderId,
            @Nullable UUID customerAccountId) {}

    private CompensationRow remedyLink(CaseRow current, UUID refId, @Nullable UUID actorMember, Instant now) {
        CompensationDirectory directory = compensationDirectories.stream()
                .filter(candidate -> CompensationKind.ORDER_REMEDY.name().equals(candidate.kind()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE_STATE, "Remedies cannot be linked here"));
        CompensationSummary summary = directory
                .summary(current.tenantId(), refId)
                .filter(found -> found.orderId().equals(current.orderId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such remedy for this order"));
        return new CompensationRow(
                current.tenantId(),
                current.id(),
                CompensationKind.ORDER_REMEDY,
                refId,
                summary.orderId(),
                actorMember,
                now,
                summary.amountMinor(),
                summary.currency());
    }

    private CompensationRow grantLink(CaseRow current, UUID refId, @Nullable UUID actorMember, Instant now) {
        BenefitGrantDirectory.GrantSummary summary = grants.summary(current.tenantId(), refId)
                .filter(found -> found.customerAccountId().equals(current.customerAccountId())
                        && found.brandId().equals(current.brandId())
                        && !"REVOKED".equals(found.status()))
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such code for this customer at this brand"));
        // A benefit grant is a count, not money: no amount, no currency.
        return new CompensationRow(
                current.tenantId(),
                current.id(),
                CompensationKind.BENEFIT_GRANT,
                summary.grantId(),
                current.orderId(),
                actorMember,
                now,
                null,
                null);
    }

    // ------------------------------------------------------------------- sweeps

    /**
     * Marks the clocks of open cases that have passed them, once each, and publishes the breaches.
     *
     * <p>Selects only open cases and takes the row lock first (see {@link JdbcRecoveryCaseStore}), so a
     * case closed before the worker reaches it is never marked, two workers cannot both mark one, and the
     * count of breaches is the count of events.
     *
     * @return how many clocks this pass marked
     */
    @Transactional
    public int markBreaches(int batchSize) {
        Instant now = clock.instant();
        List<Breach> breaches = new ArrayList<>(cases.markFirstResponseBreaches(now, batchSize));
        breaches.addAll(cases.markResolutionBreaches(now, batchSize));
        for (Breach breach : breaches) {
            CaseRow row = breach.row();
            CaseEventKind kind = RecoveryCaseSlaBreached.FIRST_RESPONSE.equals(breach.kind())
                    ? CaseEventKind.FIRST_RESPONSE_BREACHED
                    : CaseEventKind.RESOLUTION_BREACHED;
            cases.insertEvent(new EventRow(
                    Ids.newId(), row.tenantId(), row.id(), kind, null, null, null, "SYSTEM", null, null, now));
            events.publishEvent(new RecoveryCaseSlaBreached(
                    Ids.newId(),
                    row.tenantId(),
                    row.id(),
                    row.orderId(),
                    row.brandId(),
                    row.locationId(),
                    breach.kind(),
                    breach.dueAt(),
                    now));
            metrics.slaBreached(breach.kind());
        }
        return breaches.size();
    }

    // -------------------------------------------------------------------- reads

    /** A case as the worklist shows it. */
    public record CaseSummary(
            CaseRow row,
            int rating,
            SlaState slaState,
            @Nullable Person owner,
            @Nullable String orderNumber,
            int compensationCount) {}

    /** One page of the worklist and the cursor to continue it. */
    public record Listing(List<CaseSummary> items, @Nullable String nextCursor) {}

    /**
     * The worklist: open cases most-urgent first, closed ones most recently closed first.
     *
     * @param cursor the case the previous page ended on
     * @throws ApiException {@code INVALID_REQUEST} for a cursor that names no case in this scope
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Listing list(
            Scope scope,
            @Nullable CaseState state,
            @Nullable UUID ownerStaffMemberId,
            @Nullable SlaState slaState,
            @Nullable UUID cursor,
            int limit) {
        return list(scope, state, ownerStaffMemberId, slaState, null, null, cursor, limit);
    }

    /**
     * As above, narrowed to the cases about reviews rated {@code minRating} to {@code maxRating} stars (the
     * IA's «filters by rating»); either may be null. A bound outside one to five is refused rather than
     * answered with an empty list that looks like a quiet week.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Listing list(
            Scope scope,
            @Nullable CaseState state,
            @Nullable UUID ownerStaffMemberId,
            @Nullable SlaState slaState,
            @Nullable Integer minRating,
            @Nullable Integer maxRating,
            @Nullable UUID cursor,
            int limit) {
        requireRating(minRating, "minRating");
        requireRating(maxRating, "maxRating");
        if (minRating != null && maxRating != null && minRating > maxRating) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "minRating cannot be above maxRating");
        }
        CaseRow cursorKey = null;
        if (cursor != null) {
            cursorKey = cases.cursor(scope.tenantId(), scope.brandId(), scope.locationId(), cursor)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_REQUEST, "This cursor does not name a case in your scope"));
        }
        Instant now = clock.instant();
        List<Listed> page = cases.list(
                scope.tenantId(),
                scope.brandId(),
                scope.locationId(),
                state,
                ownerStaffMemberId,
                slaState,
                minRating,
                maxRating,
                now,
                cursorKey,
                limit);
        List<CaseSummary> items = summaries(scope.tenantId(), page);
        String next = page.size() < limit
                ? null
                : page.get(page.size() - 1).row().id().toString();
        return new Listing(items, next);
    }

    private static void requireRating(@Nullable Integer rating, String name) {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, name + " is a rating from 1 to 5");
        }
    }

    @Transactional(readOnly = true)
    public Counts counts(Scope scope) {
        return cases.counts(scope.tenantId(), scope.brandId(), scope.locationId(), clock.instant());
    }

    /** One entry of the timeline, with the actor resolved to a name and a note decrypted. */
    public record TimelineEntry(
            UUID id,
            CaseEventKind kind,
            @Nullable CaseState fromState,
            @Nullable CaseState toState,
            @Nullable String detailCode,
            String actorType,
            @Nullable Person actor,
            @Nullable String note,
            Instant occurredAt) {}

    /** The compensation lines on a case, with who linked each. */
    public record CompensationLine(
            CompensationRow row, @Nullable Person attachedBy) {}

    /**
     * The drawer.
     *
     * @param comment         the customer's words, only when the caller also holds {@code review.read}
     * @param hasComment      whether the review has a comment at all, so a screen can tell "withheld" from "none"
     * @param commentWithheld the review has a comment and the caller may not read it
     */
    public record CaseDetail(
            CaseSummary summary,
            List<TagView> tags,
            boolean hasComment,
            @Nullable String comment,
            boolean commentWithheld,
            @Nullable OrderSummary order,
            List<TimelineEntry> timeline,
            List<CompensationLine> compensations) {}

    @Transactional(readOnly = true)
    public CaseDetail detail(Scope scope, UUID caseId, boolean mayReadComment) {
        CaseRow found = require(scope, caseId);
        UUID tenantId = found.tenantId();
        Listed listed = cases.findListed(tenantId, caseId, clock.instant()).orElseThrow();
        Row review = reviews.findById(tenantId, found.reviewId()).orElseThrow();

        List<EventRow> eventRows = cases.events(tenantId, caseId);
        List<CompensationRow> compensationRows = cases.compensations(tenantId, caseId);
        Set<UUID> memberIds = new LinkedHashSet<>();
        if (found.ownerStaffMemberId() != null) {
            memberIds.add(found.ownerStaffMemberId());
        }
        eventRows.forEach(event -> {
            if (event.actorStaffMemberId() != null) {
                memberIds.add(event.actorStaffMemberId());
            }
        });
        compensationRows.forEach(row -> {
            if (row.attachedByStaffMemberId() != null) {
                memberIds.add(row.attachedByStaffMemberId());
            }
        });
        Map<UUID, Person> people = staff.peopleOf(tenantId, memberIds);

        List<TimelineEntry> timeline = eventRows.stream()
                .map(event -> new TimelineEntry(
                        event.id(),
                        event.kind(),
                        event.fromState(),
                        event.toState(),
                        event.detailCode(),
                        event.actorType(),
                        event.actorStaffMemberId() == null ? null : people.get(event.actorStaffMemberId()),
                        vault.revealNote(tenantId, event.id(), event.noteProtected()),
                        event.occurredAt()))
                .toList();
        List<CompensationLine> compensations = compensationRows.stream()
                .map(row -> new CompensationLine(
                        row, row.attachedByStaffMemberId() == null ? null : people.get(row.attachedByStaffMemberId())))
                .toList();

        boolean hasComment = review.commentProtected() != null;
        String comment = mayReadComment
                ? vault.revealComment(
                        tenantId, review.id(), review.commentProtected(), RevealPurpose.RECOVERY_CASE_DRAWER)
                : null;
        List<TagView> tagViews = new ArrayList<>(tags.viewsOf(
                        tenantId,
                        reviews.tagIdsFor(tenantId, List.of(review.id())).getOrDefault(review.id(), List.of()))
                .values());
        CaseSummary summary = new CaseSummary(
                found,
                listed.rating(),
                listed.slaState(),
                found.ownerStaffMemberId() == null ? null : people.get(found.ownerStaffMemberId()),
                orders.summary(tenantId, found.orderId())
                        .map(OrderDirectory.OrderSummary::publicOrderNumber)
                        .orElse(null),
                compensations.size());
        return new CaseDetail(
                summary,
                tagViews,
                hasComment,
                comment,
                hasComment && !mayReadComment,
                orders.summary(tenantId, found.orderId()).orElse(null),
                timeline,
                compensations);
    }

    // --------------------------------------------------------------- helpers

    private List<CaseSummary> summaries(UUID tenantId, List<Listed> page) {
        Set<UUID> owners = new LinkedHashSet<>();
        Set<UUID> caseIds = new LinkedHashSet<>();
        for (Listed listed : page) {
            caseIds.add(listed.row().id());
            if (listed.row().ownerStaffMemberId() != null) {
                owners.add(listed.row().ownerStaffMemberId());
            }
        }
        Map<UUID, Person> people = staff.peopleOf(tenantId, owners);
        Map<UUID, Integer> compensationCounts = cases.compensationCounts(tenantId, caseIds);
        Map<UUID, String> orderNumbers = new LinkedHashMap<>();
        List<CaseSummary> items = new ArrayList<>();
        for (Listed listed : page) {
            CaseRow row = listed.row();
            String orderNumber = orderNumbers.computeIfAbsent(
                    row.orderId(),
                    key -> orders.summary(tenantId, row.orderId())
                            .map(OrderDirectory.OrderSummary::publicOrderNumber)
                            .orElse(""));
            items.add(new CaseSummary(
                    row,
                    listed.rating(),
                    listed.slaState(),
                    row.ownerStaffMemberId() == null ? null : people.get(row.ownerStaffMemberId()),
                    orderNumber.isEmpty() ? null : orderNumber,
                    compensationCounts.getOrDefault(row.id(), 0)));
        }
        return items;
    }

    private CaseRow require(Scope scope, UUID caseId) {
        return cases.find(scope.tenantId(), caseId)
                .filter(scope::covers)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such case"));
    }

    private Person requireActiveStaff(UUID tenantId, UUID memberId) {
        return staff.person(tenantId, memberId)
                .filter(Person::active)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "That staff member does not work for this tenant"));
    }

    private ApiException stale(CaseRow seen) {
        int actual =
                cases.find(seen.tenantId(), seen.id()).map(CaseRow::version).orElse(-1);
        return ApiException.staleVersion(seen.version(), actual);
    }

    private static EventRow event(
            CaseRow current,
            CaseEventKind kind,
            @Nullable CaseState from,
            @Nullable CaseState to,
            @Nullable String detailCode,
            @Nullable UUID actorMember,
            @Nullable String noteProtected,
            Instant now) {
        return new EventRow(
                Ids.newId(),
                current.tenantId(),
                current.id(),
                kind,
                from,
                to,
                detailCode,
                "STAFF",
                actorMember,
                noteProtected,
                now);
    }
}
