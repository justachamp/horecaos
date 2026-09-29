package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Resolves the acceptance policy for an order (ADR 0002, ADR 0030).
 *
 * <p>Precedence is not implemented here. It comes from the shared mechanism, so
 * order acceptance resolves by exactly the same rule as every other scoped
 * behavior in the platform. This service adds only the platform default and the
 * pinning helper.
 */
@Service
public class OrderAcceptancePolicyService {

    /**
     * Settable at every level: a chain restaurant sets one policy for the tenant
     * and a single busy location overrides it.
     */
    public static final PolicyKey<OrderAcceptancePolicy> ACCEPTANCE = new PolicyKey<>(
            "ordering.acceptance",
            OrderAcceptancePolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "ordering",
            false,
            "How an order is accepted: automatically, or by restaurant approval.");

    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrderAcceptancePolicyService(
            PolicyResolver policies, PolicyAuthor author, AuditRecorder audit, Clock clock) {
        this.policies = policies;
        this.author = author;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The policy in force for a location right now, together with the identity
     * an order must persist so the decision stays explainable.
     */
    public Effective resolve(UUID tenantId, UUID brandId, UUID locationId) {
        return resolveAt(ResourceScope.location(tenantId, brandId, locationId));
    }

    /**
     * The same resolution, at whatever scope a caller already has — the read
     * side of Gap D's authoring surface, where a tenant may want to see what
     * is in force at the tenant or brand level without naming one location.
     */
    public Effective resolveAt(ResourceScope scope) {
        return policies.resolve(ACCEPTANCE, scope)
                .map(resolved -> new Effective(resolved.document(), resolved.policyId(), resolved.policyVersion()))
                .orElseGet(() -> new Effective(OrderAcceptancePolicy.platformDefault(), null, 0));
    }

    /**
     * Publishes the next version of the acceptance policy at a scope the
     * resolution model already supports (Gap D of the 2026-08-30 proving
     * run). Never mutates a version already in force — {@link
     * uz.horecaos.platform.tenancy.api.PolicyResolver#pinned} keeps answering
     * for every order that already resolved an earlier one, exactly as ADR
     * 0030 requires.
     */
    @Transactional
    public Effective author(ResourceScope scope, OrderAcceptancePolicy document, ActorRef authoredBy, String reason) {
        // What this scope resolved to a moment ago -- its own version, or the
        // one it inherits from a broader scope, or the platform default. That is
        // the "before" an operator means by "who changed how orders are
        // accepted": the shared mechanism's own fact (tenant.policy.authored)
        // records only that a new version exists, with a hash, and cannot say
        // the mode went from AUTO_CONFIRM to RESTAURANT_APPROVAL.
        Effective before = resolveAt(scope);
        ResolvedPolicy<OrderAcceptancePolicy> resolved = author.author(ACCEPTANCE, scope, document, authoredBy, reason);
        Effective after = new Effective(resolved.document(), resolved.policyId(), resolved.policyVersion());

        // Staff 9.3a (ADR 0027): a field-level before/after in the same
        // transaction as the publication. Only the policy's own fields and
        // versions -- the reason is the fact's "because", not a diffed field.
        audit.record(AuditFact.of("ordering.acceptance-policy.authored", AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("ordering.acceptance-policy", resolved.policyId())
                .targetVersion((long) resolved.policyVersion())
                .because(reason)
                .changed(ChangeDocuments.diff(snapshotOf(before), snapshotOf(after)))
                .correlatedBy(resolved.policyId().toString())
                .occurredAt(clock.instant())
                .build());
        return after;
    }

    private static Map<String, Object> snapshotOf(Effective effective) {
        OrderAcceptancePolicy policy = effective.policy();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", policy.mode().name());
        snapshot.put("approvalChannel", policy.approvalChannel().name());
        snapshot.put("approvalTimeoutSeconds", policy.approvalTimeoutSeconds());
        snapshot.put("timeoutAction", policy.timeoutAction().name());
        snapshot.put("rejectionReasonRequired", policy.rejectionReasonRequired());
        snapshot.put("notifyCustomerWhilePending", policy.notifyCustomerWhilePending());
        snapshot.put("policyVersion", effective.policyVersion());
        return snapshot;
    }

    /**
     * Re-resolves the exact policy an order was accepted under, so a later
     * policy change cannot alter what that order was permitted to do.
     */
    public OrderAcceptancePolicy pinned(@Nullable UUID policyId, int policyVersion) {
        if (policyId == null) {
            return OrderAcceptancePolicy.platformDefault();
        }
        return policies.pinned(ACCEPTANCE, policyId, policyVersion)
                .map(ResolvedPolicy::document)
                .orElseThrow(() -> new IllegalStateException(
                        "Order references policy %s v%d, which no longer exists".formatted(policyId, policyVersion)));
    }

    /**
     * A resolved policy and the identity to snapshot onto the order.
     *
     * @param policyId null when the platform default applied, which is itself a
     *                 fact worth recording rather than inventing an identifier for
     */
    public record Effective(
            OrderAcceptancePolicy policy, @Nullable UUID policyId, int policyVersion) {

        public boolean isPlatformDefault() {
            return policyId == null;
        }
    }
}
