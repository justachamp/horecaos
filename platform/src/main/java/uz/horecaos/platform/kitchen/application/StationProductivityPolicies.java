package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.kitchen.domain.StationProductivityPolicy;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * The reader and writer of {@code kitchen.station_productivity} (ADR 0163 Decision 4, ADR 0030).
 *
 * <p>Replace-not-merge, like every policy: a brand document replaces the tenant's rather than layering on
 * it, and a location's replaces the brand's. The built-in default is the empty document, so a tenant that
 * has not published one has no figure for any station and the plan says so ({@code NO_POLICY}); the
 * platform never supplies a number.
 */
@Service
public class StationProductivityPolicies {

    public static final String AUDIT_ACTION = "kitchen.station_productivity.published";

    private final PolicyResolver policies;
    private final KitchenPolicyEditing<StationProductivityPolicy> editing;

    public StationProductivityPolicies(PolicyResolver policies, PolicyAuthor author, AuditRecorder audit, Clock clock) {
        this.policies = policies;
        this.editing = new KitchenPolicyEditing<>(
                KitchenPolicies.STATION_PRODUCTIVITY,
                policies,
                author,
                audit,
                clock,
                AUDIT_ACTION,
                "kitchen.station_productivity",
                StationProductivityPolicy.EMPTY,
                StationProductivityPolicy::violations,
                StationProductivityPolicies::snapshotOf);
    }

    /** The document in force at a branch: the location's, else its brand's, else the tenant's, else empty. */
    public StationProductivityPolicy resolveFor(ResourceScope locationScope) {
        Optional<ResolvedPolicy<StationProductivityPolicy>> resolved =
                policies.resolve(KitchenPolicies.STATION_PRODUCTIVITY, locationScope);
        return resolved.map(ResolvedPolicy::document).orElse(StationProductivityPolicy.EMPTY);
    }

    public PolicyEditorView<StationProductivityPolicy> view(ResourceScope scope) {
        return editing.view(scope);
    }

    @Transactional
    public PolicyEditorView<StationProductivityPolicy> author(
            ResourceScope scope,
            StationProductivityPolicy document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {
        return editing.author(scope, document, expectedVersion, authoredBy, reason);
    }

    /** Flat, so the audit diff names exactly the roles and stations added, removed or changed. */
    private static Map<String, Object> snapshotOf(StationProductivityPolicy document) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        new TreeMap<>(document.roles()).forEach((role, figure) -> snapshot.put("role:" + role, figure));
        new TreeMap<>(document.stations()).forEach((code, figure) -> snapshot.put("station:" + code, figure));
        return snapshot;
    }
}
