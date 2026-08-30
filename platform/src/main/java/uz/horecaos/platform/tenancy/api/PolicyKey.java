package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.Set;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * A typed, versioned policy document key (ADR 0030).
 *
 * <p>A policy differs from a setting in one respect that matters: a durable
 * decision persists the policy identifier and version it used, so a later
 * policy change can never alter what an already-decided order, refund, or
 * approval was permitted to do.
 *
 * @param <P> the policy document type
 */
public record PolicyKey<P>(
        String code,
        Class<P> documentType,
        Set<ScopeType> settableScopes,
        String owningModule,
        boolean fieldLevelMerge,
        String description) {

    public PolicyKey {
        Objects.requireNonNull(code, "Policy code is required");
        Objects.requireNonNull(documentType, "Document type is required");
        Objects.requireNonNull(owningModule, "Owning module is required");
        settableScopes = Set.copyOf(Objects.requireNonNull(settableScopes, "Settable scopes are required"));
        if (settableScopes.isEmpty()) {
            throw new IllegalArgumentException("A policy must be settable at at least one scope: " + code);
        }
        if (!code.matches("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$")) {
            throw new IllegalArgumentException("Policy code must be dotted lower case: " + code);
        }
    }
}
