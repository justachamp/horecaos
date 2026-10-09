package uz.horecaos.platform.iam.api.terminals;

import java.util.UUID;

/**
 * Reads a tenant's terminal policy. Implemented by {@code tenancy}, which owns the configuration keys (ADR 0030); {@code
 * iam} holds the contract so the two modules do not form a cycle, the arrangement {@code TenantSuspensionLookup} already
 * uses.
 */
public interface TerminalPolicySource {

    /** The tenant's five values, resolved with the platform defaults beneath them. */
    TerminalPolicy of(UUID tenantId);
}
