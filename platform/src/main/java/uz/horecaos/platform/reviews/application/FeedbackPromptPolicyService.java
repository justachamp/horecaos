package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reviews.api.FeedbackPromptPolicy;
import uz.horecaos.platform.reviews.api.ReviewsConfigurationKeys;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument.PromptRow;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The reader and writer of {@code reviews.feedback_prompt} (ADR 0159, Decision 4, ADR 0030), and the
 * {@link FeedbackPromptPolicy} {@code notifications} asks.
 *
 * <p>One place answers "when is this customer asked, on this channel" for the prompt that is sent
 * ({@link #decide}), for the storefront's own inline prompt ({@link #inlineWindowDays}) and for the
 * screen that edits it ({@link #view}, {@link #author}), so the timing an operator reads is the timing
 * the prompt uses.
 *
 * <p>A tenant that has not switched {@code feature.feedback_settings} on is not governed at all
 * ({@link #governs}): the trigger keeps its immediate send. A tenant that has, and has never authored
 * a document, gets the built-in default; one that had an active ORDER_COMPLETED template when the
 * switch was built was given an explicit document by the V0545 preservation step, so the switch changes
 * nothing for it.
 */
@Service
public class FeedbackPromptPolicyService implements FeedbackPromptPolicy {

    public static final String AUDIT_ACTION = "reviews.prompt-policy.authored";

    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final BranchDirectory branches;
    private final ReviewsSettings settings;
    private final AuditRecorder audit;
    private final Clock clock;

    public FeedbackPromptPolicyService(
            PolicyResolver policies,
            PolicyAuthor author,
            BranchDirectory branches,
            ReviewsSettings settings,
            AuditRecorder audit,
            Clock clock) {
        this.policies = policies;
        this.author = author;
        this.branches = branches;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public boolean governs(UUID tenantId) {
        return settings.feedbackSettingsOn(tenantId);
    }

    @Override
    public Decision decide(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable String fulfillmentMode,
            String channel,
            Instant completedAt) {
        FeedbackPromptDocument document = documentAt(ResourceScope.location(tenantId, brandId, locationId));
        PromptRow row = document.rowFor(fulfillmentMode == null ? "" : fulfillmentMode, channel);
        if (!row.enabled()) {
            return Decision.off();
        }
        Instant scheduledAt = document.scheduledAt(row, completedAt, zoneOf(tenantId, brandId, locationId));
        return Decision.at(scheduledAt, document.expiresAt(row, scheduledAt));
    }

    /**
     * How many days a completed order stays in the storefront's own "rate your order" prompt. Seven
     * unless the brand's policy says otherwise; the policy governs only when the flag is on.
     */
    public int inlineWindowDays(UUID tenantId, UUID brandId) {
        if (!settings.feedbackSettingsOn(tenantId)) {
            return FeedbackPromptDocument.builtInDefault().inlineWindowDays();
        }
        return documentAt(ResourceScope.brand(tenantId, brandId)).inlineWindowDays();
    }

    // ------------------------------------------------------------------ editing

    /** The document in force at a scope, read from the table and paired with the version a save is checked against. */
    public PolicyEditor<FeedbackPromptDocument> view(ResourceScope scope) {
        requireSettable(scope);
        int versionAtScope = author.currentVersion(ReviewsConfigurationKeys.FEEDBACK_PROMPT_POLICY, scope);
        return editorOf(
                scope,
                policies.resolveUncached(ReviewsConfigurationKeys.FEEDBACK_PROMPT_POLICY, scope)
                        .orElse(null),
                versionAtScope);
    }

    /**
     * Publishes the next version at exactly {@code scope}.
     *
     * @throws ApiException {@code VALIDATION_FAILED} for a document that breaks a bound,
     *                      {@code STALE_VERSION} when the scope has moved on
     */
    @Transactional
    public PolicyEditor<FeedbackPromptDocument> author(
            ResourceScope scope,
            FeedbackPromptDocument document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {
        requireSettable(scope);
        List<String> violations = document.violations();
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", violations));
        }
        FeedbackPromptDocument before = policies.resolveUncached(ReviewsConfigurationKeys.FEEDBACK_PROMPT_POLICY, scope)
                .map(ResolvedPolicy::document)
                .orElseGet(FeedbackPromptDocument::builtInDefault);
        ResolvedPolicy<FeedbackPromptDocument> published = author.author(
                ReviewsConfigurationKeys.FEEDBACK_PROMPT_POLICY, scope, document, expectedVersion, authoredBy, reason);

        audit.record(AuditFact.of(AUDIT_ACTION, AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("reviews.feedback-prompt", published.policyId())
                .targetVersion((long) published.policyVersion())
                .because(reason)
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .changed(ChangeDocuments.diff(snapshotOf(before), snapshotOf(document)))
                .correlatedBy(published.policyId().toString())
                .occurredAt(clock.instant())
                .build());
        return editorOf(scope, published, published.policyVersion());
    }

    // ------------------------------------------------------------------ helpers

    private FeedbackPromptDocument documentAt(ResourceScope scope) {
        return policies.resolve(ReviewsConfigurationKeys.FEEDBACK_PROMPT_POLICY, scope)
                .map(ResolvedPolicy::document)
                .orElseGet(FeedbackPromptDocument::builtInDefault);
    }

    private ZoneId zoneOf(UUID tenantId, UUID brandId, UUID locationId) {
        Optional<String> timezone = branches.activeBranches(tenantId, brandId).stream()
                .filter(branch -> branch.locationId().equals(locationId))
                .map(BranchDirectory.Branch::timezone)
                .findFirst();
        try {
            return timezone.map(ZoneId::of).orElse(ZoneOffset.UTC);
        } catch (RuntimeException unknownZone) {
            return ZoneOffset.UTC;
        }
    }

    private PolicyEditor<FeedbackPromptDocument> editorOf(
            ResourceScope scope, @Nullable ResolvedPolicy<FeedbackPromptDocument> resolved, int versionAtScope) {
        List<PolicyEditor.Level> levels = new ArrayList<>();
        for (ResourceScope level : scope.chain()) {
            if (level.type() == ScopeType.PLATFORM) {
                continue;
            }
            boolean authored = level.type() == scope.type()
                    ? versionAtScope > 0
                    : author.currentVersion(ReviewsConfigurationKeys.FEEDBACK_PROMPT_POLICY, level) > 0;
            levels.add(new PolicyEditor.Level(level.type(), authored));
        }
        return new PolicyEditor<>(
                resolved == null ? FeedbackPromptDocument.builtInDefault() : resolved.document(),
                resolved == null,
                resolved == null ? null : resolved.winningScope(),
                resolved == null ? null : resolved.policyId(),
                resolved == null ? 0 : resolved.policyVersion(),
                versionAtScope,
                List.copyOf(levels));
    }

    private static void requireSettable(ResourceScope scope) {
        if (scope.type() != ScopeType.TENANT && scope.type() != ScopeType.BRAND) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The rating prompt is timed at tenant or brand scope");
        }
    }

    /**
     * The document as an audit change document. A row is keyed by its order type and channel, with
     * email written as {@code MAIL}: the audit sanitiser redacts any key that contains the word, and a
     * timing row is not an address.
     */
    static Map<String, Object> snapshotOf(FeedbackPromptDocument document) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(
                "sendWindow",
                document.sendWindow() == null
                        ? null
                        : document.sendWindow().start() + "-"
                                + document.sendWindow().end());
        snapshot.put("inlineWindowDays", document.inlineWindowDays());
        for (PromptRow row : document.rows()) {
            String channel = "EMAIL".equals(row.channel()) ? "MAIL" : row.channel();
            snapshot.put(
                    "row." + row.fulfillmentMode() + "." + channel,
                    "%s, delay %d min, give up after %d h"
                            .formatted(row.enabled() ? "on" : "off", row.delayMinutes(), row.giveUpAfterHours()));
        }
        return snapshot;
    }
}
