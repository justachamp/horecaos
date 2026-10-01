package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.iam.api.ResourceScope;

/** Resolves versioned policy documents by scope (ADR 0030). */
public interface PolicyResolver {

    <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope);

    /**
     * {@link #resolve}, read from the table rather than from the {@code
     * tenant.policy_current} cache (ADR 0033).
     *
     * <p>For a caller that sets the answer beside another read from the table and
     * acts on the pair: the policy editors show a document and send back the
     * version a save is checked against, and a cached document next to a fresh
     * version is how a form built from yesterday's numbers passes the check. The
     * boards and every other reader want {@link #resolve}; an implementation with
     * no cache needs no override.
     */
    default <P> Optional<ResolvedPolicy<P>> resolveUncached(PolicyKey<P> key, ResourceScope scope) {
        return resolve(key, scope);
    }

    /**
     * Re-resolves the exact policy version a historical decision used, so the
     * decision stays explainable after the policy changes.
     */
    <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion);
}
