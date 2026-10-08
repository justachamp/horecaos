package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.kitchen.domain.KitchenPaidHoldPolicy;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * The reader and writer of {@code kitchen.paid_hold} (ADR 0168 Decision 8, ADR 0030).
 *
 * <p>One place answers "what does the paid hold do at this branch" for the sweep that acts on it
 * ({@link #resolveFor}), for the screen that shows it ({@link #view}) and for the writer that changes it
 * ({@link #author}), so the mode an operator reads is the mode the sweep uses.
 *
 * <p>Resolution is most-specific-wins and replaces, never merges: a location that published {@code OFF}
 * keeps it whatever the platform value becomes. When nothing is published anywhere in the chain the
 * built-in {@link KitchenPaidHoldPolicy#BUILT_IN} applies, which is {@code SHADOW}: evaluate, count, hold
 * nothing. The platform document is published through {@link PolicyAuthor} at platform scope by the owner,
 * an operator-run publication audited like any other; no platform-scoped endpoint exists.
 */
@Service
public class KitchenPaidHoldPolicies {

    public static final String AUDIT_ACTION = "kitchen.paid_hold.published";

    private final PolicyResolver policies;
    private final KitchenPolicyEditing<KitchenPaidHoldPolicy> editing;

    public KitchenPaidHoldPolicies(PolicyResolver policies, PolicyAuthor author, AuditRecorder audit, Clock clock) {
        this.policies = policies;
        this.editing = new KitchenPolicyEditing<>(
                KitchenPolicies.PAID_HOLD,
                policies,
                author,
                audit,
                clock,
                AUDIT_ACTION,
                "kitchen.paid_hold",
                KitchenPaidHoldPolicy.BUILT_IN,
                KitchenPaidHoldPolicy::violations,
                KitchenPaidHoldPolicies::snapshotOf);
    }

    /**
     * The policy in force at a branch, with the identity a hold pins, for the sweep.
     *
     * @return the resolved document; {@link Resolved#identity()} is empty when it is the built-in default
     */
    public Resolved resolveFor(UUID tenantId, UUID brandId, UUID locationId) {
        Optional<ResolvedPolicy<KitchenPaidHoldPolicy>> resolved =
                policies.resolve(KitchenPolicies.PAID_HOLD, ResourceScope.location(tenantId, brandId, locationId));
        return resolved.map(found -> new Resolved(found.document(), Optional.of(found)))
                .orElseGet(() -> new Resolved(KitchenPaidHoldPolicy.BUILT_IN, Optional.empty()));
    }

    public PolicyEditorView<KitchenPaidHoldPolicy> view(ResourceScope scope) {
        return editing.view(scope);
    }

    @Transactional
    public PolicyEditorView<KitchenPaidHoldPolicy> author(
            ResourceScope scope,
            KitchenPaidHoldPolicy document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {
        return editing.author(scope, document, expectedVersion, authoredBy, reason);
    }

    private static Map<String, Object> snapshotOf(KitchenPaidHoldPolicy document) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", document.mode().name());
        snapshot.put("overdueAfterMinutes", document.overdueAfterMinutes());
        return snapshot;
    }

    /** A policy and, when one was published, the identity to pin beside a hold. */
    public record Resolved(KitchenPaidHoldPolicy document, Optional<ResolvedPolicy<KitchenPaidHoldPolicy>> identity) {

        public KitchenPaidHoldPolicy.Mode mode() {
            return document.mode();
        }
    }
}
