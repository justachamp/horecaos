package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.iam.api.ResourceScope;

/** Resolves versioned policy documents by scope (ADR 0030). */
public interface PolicyResolver {

    <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope);

    /**
     * Re-resolves the exact policy version a historical decision used, so the
     * decision stays explainable after the policy changes.
     */
    <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion);
}
