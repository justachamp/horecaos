package uz.horecaos.platform.iam.api.terminals;

import java.time.Instant;
import java.util.UUID;

/**
 * Whether a branch is in service at an instant (ADR 0155, Observability): the question the «terminal unseen» alert asks,
 * because a tablet that goes quiet when the kitchen has closed for the night is not an incident. Implemented by {@code
 * tenancy}, which owns the service schedule (ADR 0036); {@code iam} holds the contract so the two modules do not form a
 * cycle, the arrangement {@link TerminalPolicySource} already uses.
 */
public interface TerminalServiceHours {

    /** True when some channel at the branch may take an order for immediate service at {@code at}. */
    boolean inService(UUID tenantId, UUID brandId, UUID locationId, Instant at);
}
