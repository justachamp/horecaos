package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Reading and publishing one ADR 0030 policy document for an operator screen: the shape {@code
 * PaymentWindowAuthoringService} established, written once for the kitchen's two (ADR 0163, ADR 0168).
 *
 * <p>An editor answers the document in force at a scope <em>read from the table</em>, never from the
 * resolver's cache, and the version authored at exactly that scope (0 when it inherits): the {@code
 * If-Match} a save is checked against. A save is a whole-document replace, validated before it is
 * published, and writes one ADR 0027 {@code BUSINESS} fact whose change document is a flat diff of the
 * snapshot the owning service supplies.
 *
 * @param <P> the policy document
 */
final class KitchenPolicyEditing<P> {

    private final PolicyKey<P> key;
    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String auditAction;
    private final String targetType;
    private final P builtIn;
    private final Function<P, List<String>> violations;
    private final Function<P, Map<String, Object>> snapshot;

    KitchenPolicyEditing(
            PolicyKey<P> key,
            PolicyResolver policies,
            PolicyAuthor author,
            AuditRecorder audit,
            Clock clock,
            String auditAction,
            String targetType,
            P builtIn,
            Function<P, List<String>> violations,
            Function<P, Map<String, Object>> snapshot) {
        this.key = key;
        this.policies = policies;
        this.author = author;
        this.audit = audit;
        this.clock = clock;
        this.auditAction = auditAction;
        this.targetType = targetType;
        this.builtIn = builtIn;
        this.violations = violations;
        this.snapshot = snapshot;
    }

    PolicyEditorView<P> view(ResourceScope scope) {
        requireSettable(scope);
        int versionAtScope = author.currentVersion(key, scope);
        return editorOf(scope, policies.resolveUncached(key, scope).orElse(null), versionAtScope);
    }

    PolicyEditorView<P> author(
            ResourceScope scope, P document, int expectedVersion, ActorRef authoredBy, String reason) {
        requireSettable(scope);
        List<String> found = violations.apply(document);
        if (!found.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", found));
        }
        P before = policies.resolveUncached(key, scope)
                .map(ResolvedPolicy::document)
                .orElse(builtIn);
        ResolvedPolicy<P> published = author.author(key, scope, document, expectedVersion, authoredBy, reason);
        audit.record(AuditFact.of(auditAction, AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target(targetType, published.policyId())
                .targetVersion((long) published.policyVersion())
                .because(reason)
                .changed(ChangeDocuments.diff(snapshot.apply(before), snapshot.apply(document)))
                .correlatedBy(published.policyId().toString())
                .occurredAt(clock.instant())
                .build());
        return editorOf(scope, published, published.policyVersion());
    }

    private PolicyEditorView<P> editorOf(
            ResourceScope scope, @Nullable ResolvedPolicy<P> resolved, int versionAtScope) {
        List<PolicyEditorView.Level> levels = new ArrayList<>();
        for (ResourceScope level : scope.chain()) {
            if (!key.settableScopes().contains(level.type())) {
                continue;
            }
            boolean authored =
                    level.type() == scope.type() ? versionAtScope > 0 : author.currentVersion(key, level) > 0;
            levels.add(new PolicyEditorView.Level(level.type(), authored));
        }
        return new PolicyEditorView<>(
                resolved == null ? builtIn : resolved.document(),
                resolved == null,
                resolved == null ? null : resolved.winningScope(),
                resolved == null ? null : resolved.policyId(),
                resolved == null ? 0 : resolved.policyVersion(),
                versionAtScope,
                List.copyOf(levels));
    }

    private void requireSettable(ResourceScope scope) {
        if (!key.settableScopes().contains(scope.type())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s is not set at %s scope"
                            .formatted(key.code(), scope.type().name().toLowerCase()));
        }
    }
}
