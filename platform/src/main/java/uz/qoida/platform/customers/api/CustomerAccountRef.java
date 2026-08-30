package uz.qoida.platform.customers.api;

import java.util.Objects;
import java.util.UUID;

/**
 * What other modules hold for a customer (ADR 0015).
 *
 * <p>An id and a tenant, never a phone number or an email. Ordering needs to know
 * whose order this is; it does not need, and must not accumulate, the personal
 * data that ADR 0029 keeps encrypted in one place.
 */
public record CustomerAccountRef(UUID accountId, UUID tenantId) {

    public CustomerAccountRef {
        Objects.requireNonNull(accountId, "A customer account id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
    }
}
