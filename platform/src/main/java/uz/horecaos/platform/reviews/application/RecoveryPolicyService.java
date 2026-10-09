package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.reviews.api.RecoveryAlertSettings;
import uz.horecaos.platform.reviews.api.ReviewsConfigurationKeys;
import uz.horecaos.platform.reviews.domain.RecoveryPolicyDocument;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The reader and writer of {@code reviews.recovery} (ADR 0159, Decisions 8 and 10, ADR 0030): the rating
 * that opens a case and the two clocks staff have.
 *
 * <p>A case stores the policy version it was opened under, so {@link #resolveAt} hands back the identity
 * with the document -- the caller persists it -- and a later edit never makes an old case late.
 */
@Service
public class RecoveryPolicyService implements RecoveryAlertSettings {

    public static final String AUDIT_ACTION = "reviews.recovery-policy.authored";

    /** A policy and the identity a case must remember it by; version 0 means the built-in default. */
    public record InForce(RecoveryPolicyDocument document, int version) {}

    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final AuditRecorder audit;
    private final Clock clock;

    public RecoveryPolicyService(PolicyResolver policies, PolicyAuthor author, AuditRecorder audit, Clock clock) {
        this.policies = policies;
        this.author = author;
        this.audit = audit;
        this.clock = clock;
    }

    /** The document in force for a brand, with the version to store on a case. */
    public InForce resolveAt(UUID tenantId, UUID brandId) {
        return policies.resolve(ReviewsConfigurationKeys.RECOVERY_POLICY, ResourceScope.brand(tenantId, brandId))
                .map(found -> new InForce(found.document(), found.policyVersion()))
                .orElseGet(() -> new InForce(RecoveryPolicyDocument.builtInDefault(), 0));
    }

    @Override
    public boolean alertOnOpen(UUID tenantId, UUID brandId) {
        return resolveAt(tenantId, brandId).document().alertOnOpen();
    }

    @Override
    public boolean alertOnBreach(UUID tenantId, UUID brandId) {
        return resolveAt(tenantId, brandId).document().alertOnBreach();
    }

    public PolicyEditor<RecoveryPolicyDocument> view(ResourceScope scope) {
        requireSettable(scope);
        int versionAtScope = author.currentVersion(ReviewsConfigurationKeys.RECOVERY_POLICY, scope);
        return editorOf(
                scope,
                policies.resolveUncached(ReviewsConfigurationKeys.RECOVERY_POLICY, scope)
                        .orElse(null),
                versionAtScope);
    }

    @Transactional
    public PolicyEditor<RecoveryPolicyDocument> author(
            ResourceScope scope,
            RecoveryPolicyDocument document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {
        requireSettable(scope);
        List<String> violations = document.violations();
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", violations));
        }
        RecoveryPolicyDocument before = policies.resolveUncached(ReviewsConfigurationKeys.RECOVERY_POLICY, scope)
                .map(ResolvedPolicy::document)
                .orElseGet(RecoveryPolicyDocument::builtInDefault);
        ResolvedPolicy<RecoveryPolicyDocument> published = author.author(
                ReviewsConfigurationKeys.RECOVERY_POLICY, scope, document, expectedVersion, authoredBy, reason);

        audit.record(AuditFact.of(AUDIT_ACTION, AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("reviews.recovery-policy", published.policyId())
                .targetVersion((long) published.policyVersion())
                .because(reason)
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .changed(ChangeDocuments.diff(snapshotOf(before), snapshotOf(document)))
                .correlatedBy(published.policyId().toString())
                .occurredAt(clock.instant())
                .build());
        return editorOf(scope, published, published.policyVersion());
    }

    private PolicyEditor<RecoveryPolicyDocument> editorOf(
            ResourceScope scope, @Nullable ResolvedPolicy<RecoveryPolicyDocument> resolved, int versionAtScope) {
        List<PolicyEditor.Level> levels = new ArrayList<>();
        for (ResourceScope level : scope.chain()) {
            if (level.type() == ScopeType.PLATFORM) {
                continue;
            }
            boolean authored = level.type() == scope.type()
                    ? versionAtScope > 0
                    : author.currentVersion(ReviewsConfigurationKeys.RECOVERY_POLICY, level) > 0;
            levels.add(new PolicyEditor.Level(level.type(), authored));
        }
        return new PolicyEditor<>(
                resolved == null ? RecoveryPolicyDocument.builtInDefault() : resolved.document(),
                resolved == null,
                resolved == null ? null : resolved.winningScope(),
                resolved == null ? null : resolved.policyId(),
                resolved == null ? 0 : resolved.policyVersion(),
                versionAtScope,
                List.copyOf(levels));
    }

    private static void requireSettable(ResourceScope scope) {
        if (scope.type() != ScopeType.TENANT && scope.type() != ScopeType.BRAND) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The recovery policy is set at tenant or brand scope");
        }
    }

    static Map<String, Object> snapshotOf(RecoveryPolicyDocument document) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        // "Stars", not "Rating": the audit sanitiser redacts any key containing "tin", and "rating" does.
        snapshot.put("autoOpenMaxStars", document.autoOpenMaxRating());
        snapshot.put("firstResponseMinutes", document.firstResponseMinutes());
        snapshot.put("resolutionMinutes", document.resolutionMinutes());
        snapshot.put("alertOnOpen", document.alertOnOpen());
        snapshot.put("alertOnBreach", document.alertOnBreach());
        return snapshot;
    }
}
