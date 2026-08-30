package uz.qoida.platform.integration.api.provider;

import java.util.Optional;
import java.util.Set;

/**
 * The capabilities a wired provider adapter implements (ADR 0026).
 *
 * <p>This is deliberately an adapter declaration, not a claim about a tenant's
 * credentials. Reconciliation combines it with a successful secret-reference
 * preflight and records both pieces of evidence. An adapter must not manufacture
 * a live provider call merely to satisfy this interface: on several providers,
 * the only authenticated calls create a charge, booking, or notification.
 */
public interface ProviderCapabilityCatalog {

    ProviderCategory category();

    /** Empty means this build has no adapter for the requested provider type. */
    Optional<Declaration> declarationFor(String providerType);

    /** A versioned, non-sensitive description of one adapter's implemented surface. */
    record Declaration(Set<String> capabilities, String adapterVersion) {

        public Declaration {
            capabilities = Set.copyOf(capabilities);
            if (adapterVersion == null || adapterVersion.isBlank()) {
                throw new IllegalArgumentException("An adapter version is required");
            }
        }
    }
}
