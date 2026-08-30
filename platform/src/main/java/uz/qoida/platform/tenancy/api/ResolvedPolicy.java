package uz.qoida.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;

import uz.qoida.platform.iam.api.ResourceScope.ScopeType;

/**
 * A resolved policy document with the identity a caller must persist (ADR 0030).
 *
 * <p>A caller making a durable decision stores {@link #policyId()} and
 * {@link #policyVersion()} alongside the business fact. Re-reading that fact
 * later uses {@code PolicyResolver.pinned} so the original policy applies, not
 * today's.
 */
public record ResolvedPolicy<P>(
        String keyCode,
        UUID policyId,
        int policyVersion,
        ScopeType winningScope,
        String documentHash,
        P document) {

    public ResolvedPolicy {
        Objects.requireNonNull(keyCode, "Key code is required");
        Objects.requireNonNull(policyId, "Policy ID is required");
        Objects.requireNonNull(document, "Policy document is required");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("Policy version must be positive");
        }
    }
}
