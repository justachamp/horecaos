package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pricing.api.PricingConfigurationKeys;
import uz.horecaos.platform.pricing.api.PromotionActivated;
import uz.horecaos.platform.pricing.api.PromotionSuspended;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcPromotionReferences;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.DefinitionVersionRow;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.PromotionRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring a brand's automatic promotions and markups (ADR 0140).
 *
 * <p>A lifecycle with a validator, an approval and an immutable history:
 * {@code DRAFT -> VALIDATED -> ACTIVE <-> SUSPENDED -> ARCHIVED}, as V0093's
 * CHECK already says. An {@code ACTIVE} promotion is never edited in place -- the
 * marketer suspends it, edits it back to {@code DRAFT} (which bumps the
 * definition version once the version being left has been recorded), validates and
 * reactivates -- so there is never a live/pending pair of definitions, and an old
 * quote is always explainable against the definition recorded for the version
 * that priced it. The one exception is a reorder of priorities, which breaks ties
 * and never creates a benefit; it is audited and versioned like any change.
 *
 * <p>Every validate and every activation appends an immutable row to {@code
 * pricing.promotion_definition_versions}. Every step writes its ADR 0027 audit
 * fact in the same transaction, through {@link ChangeDocuments}.
 *
 * <p>Activation needs a second person when the promotion is a markup, or its
 * largest percentage or fixed amount exceeds a threshold read through ADR 0030,
 * and is otherwise direct. The second person is the ADR 0027 mechanism: the
 * request is raised by whoever pressed activate, only someone else may decide it,
 * and the approval is spent by the activation it authorised.
 *
 * <p>Promo codes are not authored here: they stay the coupon-gated face of a
 * promotion and keep their own surface (ADR 0072).
 */
@Service
public class PromotionAuthoringService {

    private final JdbcPromotionStore store;
    private final JdbcPromotionReferences references;
    private final ConfigurationResolver configuration;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PromotionAuthoringService(
            JdbcPromotionStore store,
            JdbcPromotionReferences references,
            ConfigurationResolver configuration,
            ApprovalService approvals,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            CurrentActor currentActor,
            Clock clock) {
        this.store = store;
        this.references = references;
        this.configuration = configuration;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    /** One promotion with its recorded definition versions. */
    public record Detail(PromotionRow promotion, List<DefinitionVersionRow> versions) {}

    /** The outcome of a validation: the report, and the promotion as it now stands. */
    public record ValidationResult(PromotionValidator.Report report, PromotionRow promotion) {}

    /** The outcome of an activation: it either happened, or a second person has been asked. */
    public record ActivationResult(
            @Nullable PromotionRow promotion, @Nullable UUID pendingApprovalRequestId) {

        public boolean isPending() {
            return pendingApprovalRequestId != null;
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PromotionRow> list(UUID tenantId, UUID brandId) {
        return store.list(tenantId, brandId);
    }

    @Transactional(readOnly = true)
    public Detail get(UUID tenantId, UUID brandId, UUID id) {
        PromotionRow row = require(tenantId, brandId, id);
        return new Detail(row, store.definitionVersions(tenantId, brandId, id));
    }

    /** The bounded redemptions drill-down: ids and amounts only, never a name or a contact. */
    @Transactional(readOnly = true)
    public List<JdbcPromotionStore.LedgerRow> redemptions(UUID tenantId, UUID brandId, UUID id) {
        require(tenantId, brandId, id);
        return store.redemptionsForPromotion(tenantId, brandId, id, REDEMPTION_PAGE);
    }

    private static final int REDEMPTION_PAGE = 200;

    // ------------------------------------------------------------- authoring

    @Transactional
    public PromotionRow create(UUID tenantId, UUID brandId, PromotionDefinition draft) {
        PromotionDefinition definition = normalised(draft);
        UUID id = uz.horecaos.platform.configuration.Ids.newId();
        Instant now = clock.instant();
        try {
            store.insertDraft(id, tenantId, brandId, definition, now);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This brand already has a promotion with the handle " + definition.code());
        }
        PromotionRow created = require(tenantId, brandId, id);
        audit.record(AuditFact.of("pricing.promotion.drafted", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Promotion", id)
                .targetVersion((long) created.version())
                .because("Promotion drafted")
                .usingCapability(Capability.PRICING_PROMOTION_MANAGE.code())
                .changed(ChangeDocuments.created(snapshot(created)))
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());
        return created;
    }

    /**
     * Replaces the definition and returns the promotion to {@code DRAFT}.
     *
     * <p>Refused while {@code ACTIVE}: suspend it first. The definition version is
     * bumped when the version being left was ever recorded in the history (that is,
     * validated), so a recorded version is never overwritten.
     */
    @Transactional
    public PromotionRow update(
            UUID tenantId, UUID brandId, UUID id, int expectedVersion, PromotionDefinition replacement) {
        PromotionRow before = require(tenantId, brandId, id);
        if ("ACTIVE".equals(before.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An active promotion is never edited in place; suspend it, edit it, validate it and activate it again");
        }
        if ("ARCHIVED".equals(before.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "An archived promotion is history and cannot be edited");
        }
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        PromotionDefinition definition = normalised(replacement);
        if (definition.maximumRedemptions() != null && definition.maximumRedemptions() < before.consumedCount()) {
            // The database refuses it too (ck_promotion_consumed), as an integrity error nobody
            // maps; say it as the authoring refusal it is.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This promotion has already been redeemed " + before.consumedCount()
                            + " times, so its limit cannot be set below that",
                    java.util.Map.of("reason", "MAXIMUM_REDEMPTIONS_BELOW_CONSUMED"));
        }
        int definitionVersion = store.hasDefinitionVersion(tenantId, id, before.definitionVersion())
                ? before.definitionVersion() + 1
                : before.definitionVersion();
        Instant now = clock.instant();
        boolean replaced;
        try {
            replaced =
                    store.replaceDefinition(tenantId, brandId, id, expectedVersion, definition, definitionVersion, now);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This brand already has a promotion with the handle " + definition.code());
        }
        if (!replaced) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The promotion changed while it was being edited");
        }
        PromotionRow after = require(tenantId, brandId, id);
        audit.record(AuditFact.of("pricing.promotion.updated", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Promotion", id)
                .targetVersion((long) after.version())
                .because("Promotion edited")
                .usingCapability(Capability.PRICING_PROMOTION_MANAGE.code())
                .changed(ChangeDocuments.diff(snapshot(before), snapshot(after)))
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());
        return after;
    }

    /**
     * Runs the rule checker. A refusal leaves the promotion in {@code DRAFT} and
     * returns the report; a pass moves it to {@code VALIDATED} and records the
     * definition in the immutable history.
     */
    @Transactional
    public ValidationResult validate(UUID tenantId, UUID brandId, UUID id, int expectedVersion) {
        PromotionRow row = require(tenantId, brandId, id);
        if (row.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, row.version());
        }
        if (!"DRAFT".equals(row.status()) && !"VALIDATED".equals(row.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "Only a draft can be validated; this promotion is " + row.status());
        }
        PromotionValidator.Report report = check(tenantId, brandId, id, row.definition());
        if (!report.isValid()) {
            return new ValidationResult(report, row);
        }
        if ("VALIDATED".equals(row.status())) {
            return new ValidationResult(report, row);
        }
        Instant now = clock.instant();
        if (!store.transition(tenantId, brandId, id, expectedVersion, List.of("DRAFT"), "VALIDATED", now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The promotion changed while it was being validated");
        }
        store.appendDefinitionVersion(
                tenantId, brandId, id, row.definitionVersion(), row.definition(), actor().subject(), "VALIDATED", now);
        PromotionRow after = require(tenantId, brandId, id);
        recordTransition("pricing.promotion.validated", "Promotion validated", row, after, now);
        return new ValidationResult(report, after);
    }

    /**
     * Puts a validated promotion in front of customers.
     *
     * <p>Asks for a second person first when a markup, or a percentage or fixed
     * amount over the configured thresholds, is involved. The report is re-run: the
     * world a definition was validated against can move before it is activated.
     */
    @Transactional
    public ActivationResult activate(
            UUID tenantId, UUID brandId, UUID id, int expectedVersion, @Nullable String reason) {
        PromotionRow row = require(tenantId, brandId, id);
        if (row.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, row.version());
        }
        if (!"VALIDATED".equals(row.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Only a validated promotion can be activated; this one is " + row.status());
        }
        PromotionValidator.Report report = check(tenantId, brandId, id, row.definition());
        if (!report.isValid()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The promotion no longer passes validation: "
                            + report.refusals().get(0).code());
        }

        UUID approvalId = null;
        if (needsSecondPerson(tenantId, brandId, row.definition())) {
            ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                    ApprovalAction.PRICING_PROMOTION_ACTIVATE.code(),
                    ApprovalParameters.of(new ActivationApprovalParameters(
                                    id,
                                    row.definitionVersion(),
                                    row.definition().kind().name()))
                            .excluding()
                            .hash(),
                    ResourceScope.brand(tenantId, brandId),
                    actor(),
                    reason == null || reason.isBlank() ? "Promotion activation" : reason,
                    ApprovalRequestCommand.DEFAULT_VALIDITY));
            switch (outcome) {
                case ApprovalOutcome.NotRequired notRequired -> {
                    /* No policy resolved a threshold for this action; activate directly. */
                }
                case ApprovalOutcome.Approved approved -> {
                    outcome.consume();
                    approvalId = approved.requestId();
                }
                case ApprovalOutcome.Pending pending -> {
                    return new ActivationResult(null, pending.requestId());
                }
                case ApprovalOutcome.Declined declined ->
                    throw new ApiException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "The approval for this activation was declined: " + declined.reason());
            }
        }

        Instant now = clock.instant();
        if (!store.activate(tenantId, brandId, id, expectedVersion, actor().subject(), approvalId, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The promotion changed while it was being activated");
        }
        store.appendDefinitionVersion(
                tenantId, brandId, id, row.definitionVersion(), row.definition(), actor().subject(), "ACTIVATED", now);
        PromotionRow after = require(tenantId, brandId, id);
        recordTransition("pricing.promotion.activated", "Promotion activated", row, after, now);
        events.publishEvent(activated(after, now));
        return new ActivationResult(after, null);
    }

    @Transactional
    public PromotionRow suspend(UUID tenantId, UUID brandId, UUID id, int expectedVersion) {
        PromotionRow row = require(tenantId, brandId, id);
        PromotionRow after = step(tenantId, brandId, row, expectedVersion, Set.of("ACTIVE"), "SUSPENDED");
        Instant now = clock.instant();
        recordTransition("pricing.promotion.suspended", "Promotion suspended", row, after, now);
        events.publishEvent(suspended(after, now));
        return after;
    }

    /** Resumes a suspended promotion: the same definition, re-checked against the world as it is now. */
    @Transactional
    public PromotionRow resume(UUID tenantId, UUID brandId, UUID id, int expectedVersion) {
        PromotionRow row = require(tenantId, brandId, id);
        if (!"SUSPENDED".equals(row.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Only a suspended promotion can be resumed; this one is " + row.status());
        }
        PromotionValidator.Report report = check(tenantId, brandId, id, row.definition());
        if (!report.isValid()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The promotion no longer passes validation: "
                            + report.refusals().get(0).code());
        }
        PromotionRow after = step(tenantId, brandId, row, expectedVersion, Set.of("SUSPENDED"), "ACTIVE");
        Instant now = clock.instant();
        recordTransition("pricing.promotion.resumed", "Promotion resumed", row, after, now);
        events.publishEvent(activated(after, now));
        return after;
    }

    @Transactional
    public PromotionRow archive(UUID tenantId, UUID brandId, UUID id, int expectedVersion) {
        PromotionRow row = require(tenantId, brandId, id);
        PromotionRow after = step(
                tenantId,
                brandId,
                row,
                expectedVersion,
                Set.of("DRAFT", "VALIDATED", "ACTIVE", "SUSPENDED"),
                "ARCHIVED");
        Instant now = clock.instant();
        recordTransition("pricing.promotion.archived", "Promotion archived", row, after, now);
        if ("ACTIVE".equals(row.status())) {
            // An archived promotion stops applying exactly as a suspended one does.
            events.publishEvent(suspended(after, now));
        }
        return after;
    }

    /**
     * Reorders priorities within one stacking group: the first id listed gets the
     * highest priority. Priority only breaks ties and never creates a benefit, so
     * it is the one change an {@code ACTIVE} promotion accepts without going back
     * to {@code DRAFT}; each promotion whose priority actually moves gets a new
     * definition version and a history row, so an old quote still names the
     * definition it was priced under.
     */
    @Transactional
    public List<PromotionRow> reorder(UUID tenantId, UUID brandId, String stackingGroup, List<UUID> orderedIds) {
        if (orderedIds.isEmpty() || orderedIds.stream().distinct().count() != orderedIds.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Name each promotion of the group once");
        }
        Map<UUID, PromotionRow> inGroup = new LinkedHashMap<>();
        for (PromotionRow row : store.list(tenantId, brandId)) {
            if (row.definition().stackingGroup().equals(stackingGroup) && !"ARCHIVED".equals(row.status())) {
                inGroup.put(row.id(), row);
            }
        }
        for (UUID id : orderedIds) {
            if (!inGroup.containsKey(id)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Promotion " + id + " is not a live promotion of the stacking group " + stackingGroup);
            }
        }
        Instant now = clock.instant();
        List<PromotionRow> changed = new ArrayList<>();
        int total = orderedIds.size();
        for (int position = 0; position < total; position++) {
            UUID id = orderedIds.get(position);
            PromotionRow row = Objects.requireNonNull(inGroup.get(id), "checked to be in the group above");
            int priority = total - position;
            if (row.definition().priority() == priority) {
                continue;
            }
            int newDefinitionVersion = row.definitionVersion() + 1;
            if (!store.reprioritise(tenantId, brandId, id, priority, newDefinitionVersion, now)) {
                throw new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "The promotion changed while it was being reordered");
            }
            PromotionRow after = require(tenantId, brandId, id);
            store.appendDefinitionVersion(
                    tenantId,
                    brandId,
                    id,
                    newDefinitionVersion,
                    after.definition(),
                    actor().subject(),
                    "REPRIORITISED",
                    now);
            recordTransition("pricing.promotion.updated", "Promotion priority reordered", row, after, now);
            changed.add(after);
        }
        return changed;
    }

    // ------------------------------------------------------------- internals

    private PromotionRow step(
            UUID tenantId, UUID brandId, PromotionRow row, int expectedVersion, Set<String> allowedFrom, String to) {
        if (!allowedFrom.contains(row.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "A promotion that is " + row.status() + " cannot move this way");
        }
        if (row.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, row.version());
        }
        if (!store.transition(
                tenantId, brandId, row.id(), expectedVersion, List.copyOf(allowedFrom), to, clock.instant())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The promotion changed while it was being updated");
        }
        return require(tenantId, brandId, row.id());
    }

    private PromotionValidator.Report check(UUID tenantId, UUID brandId, UUID id, PromotionDefinition definition) {
        List<PromotionValidator.Peer> peers = store.list(tenantId, brandId).stream()
                .filter(other -> !"ARCHIVED".equals(other.status()))
                .map(other -> new PromotionValidator.Peer(
                        other.id(),
                        other.definition().stackingGroup(),
                        other.definition().kind(),
                        other.definition().scope()))
                .toList();
        return PromotionValidator.validate(
                id, definition, peers, references.forBrand(tenantId, brandId, clock.instant()));
    }

    /** The definition as the engine will read it: a handle with no stray whitespace, never coupon-gated from here. */
    private static PromotionDefinition normalised(PromotionDefinition draft) {
        return new PromotionDefinition(
                draft.code() == null ? "" : draft.code().strip(),
                draft.name() == null ? "" : draft.name().strip(),
                draft.kind(),
                draft.scope(),
                draft.stackingGroup() == null ? "" : draft.stackingGroup().strip(),
                draft.exclusive(),
                draft.priority(),
                false,
                draft.maximumDiscountMinor(),
                draft.currency() == null ? "" : draft.currency().strip().toUpperCase(java.util.Locale.ROOT),
                draft.validFrom(),
                draft.validUntil(),
                draft.maximumRedemptions(),
                draft.maximumPerCustomer(),
                draft.loyaltyAccrual(),
                draft.loyaltyRedemption(),
                draft.conditions(),
                draft.actions());
    }

    private boolean needsSecondPerson(UUID tenantId, UUID brandId, PromotionDefinition definition) {
        ResourceScope scope = ResourceScope.brand(tenantId, brandId);
        if (definition.kind() == Promotion.Kind.MARKUP
                && Boolean.TRUE.equals(
                        configuration.value(PricingConfigurationKeys.PROMOTION_APPROVAL_ALWAYS_FOR_MARKUP, scope))) {
            return true;
        }
        long percentageThreshold = Objects.requireNonNull(
                configuration.value(PricingConfigurationKeys.PROMOTION_APPROVAL_PERCENTAGE_OVER_BP, scope),
                "declares a code default and never terminates on explicit null");
        long amountThreshold = Objects.requireNonNull(
                configuration.value(PricingConfigurationKeys.PROMOTION_APPROVAL_AMOUNT_OVER_MINOR, scope),
                "declares a code default and never terminates on explicit null");
        long largestPercentage = 0;
        long largestAmount = 0;
        for (PromotionDefinition.ActionDefinition action : definition.actions()) {
            Object basisPoints = action.operands().get("basisPoints");
            if (basisPoints instanceof Number number) {
                largestPercentage = Math.max(largestPercentage, number.longValue());
            }
            Object amount = action.operands().get("amountMinor");
            if (amount instanceof Number number) {
                largestAmount = Math.max(largestAmount, number.longValue());
            }
        }
        return largestPercentage > percentageThreshold || largestAmount > amountThreshold;
    }

    /** What a signature on an activation covers: which promotion, which definition version, which kind. */
    private record ActivationApprovalParameters(UUID promotionId, int definitionVersion, String kind) {}

    private PromotionRow require(UUID tenantId, UUID brandId, UUID id) {
        return store.find(tenantId, brandId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such promotion"));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private void recordTransition(String action, String reason, PromotionRow before, PromotionRow after, Instant now) {
        audit.record(AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(after.tenantId(), after.brandId()))
                .target("Promotion", after.id())
                .targetVersion((long) after.version())
                .because(reason)
                .usingCapability(Capability.PRICING_PROMOTION_MANAGE.code())
                .changed(ChangeDocuments.diff(snapshot(before), snapshot(after)))
                .correlatedBy(after.id().toString())
                .occurredAt(now)
                .build());
    }

    private static Map<String, Object> snapshot(PromotionRow row) {
        PromotionDefinition d = row.definition();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("code", d.code());
        document.put("name", d.name());
        document.put("kind", d.kind().name());
        document.put("scope", d.scope().name());
        document.put("stackingGroup", d.stackingGroup());
        document.put("exclusive", d.exclusive());
        document.put("priority", d.priority());
        document.put("status", row.status());
        document.put("definitionVersion", row.definitionVersion());
        document.put("maximumDiscountMinor", d.maximumDiscountMinor() == null ? "none" : d.maximumDiscountMinor());
        document.put("validFrom", d.validFrom() == null ? "none" : d.validFrom().toString());
        document.put(
                "validUntil", d.validUntil() == null ? "none" : d.validUntil().toString());
        document.put("maximumRedemptions", d.maximumRedemptions() == null ? "none" : d.maximumRedemptions());
        document.put("maximumPerCustomer", d.maximumPerCustomer() == null ? "none" : d.maximumPerCustomer());
        document.put("loyaltyAccrual", d.loyaltyAccrual().name());
        document.put("loyaltyRedemption", d.loyaltyRedemption().name());
        document.put("conditionCount", d.conditions().size());
        document.put("actionCount", d.actions().size());
        return document;
    }

    private PromotionActivated activated(PromotionRow row, Instant now) {
        PromotionDefinition d = row.definition();
        return new PromotionActivated(
                uz.horecaos.platform.configuration.Ids.newId(),
                row.tenantId(),
                row.brandId(),
                row.id(),
                row.definitionVersion(),
                d.scope().name(),
                d.kind().name(),
                d.validFrom() == null ? now : d.validFrom(),
                d.validUntil(),
                now);
    }

    private PromotionSuspended suspended(PromotionRow row, Instant now) {
        PromotionDefinition d = row.definition();
        return new PromotionSuspended(
                uz.horecaos.platform.configuration.Ids.newId(),
                row.tenantId(),
                row.brandId(),
                row.id(),
                row.definitionVersion(),
                d.scope().name(),
                d.kind().name(),
                now);
    }
}
