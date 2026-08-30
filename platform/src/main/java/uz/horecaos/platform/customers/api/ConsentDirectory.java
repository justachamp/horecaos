package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading what a customer has agreed to (ADR 0015, consumed by ADR 0020).
 *
 * <p>A read, never a decision. Notifications must not form its own opinion about
 * whether a message is permitted: consent is an append-only record with a policy
 * version and evidence behind it, and a second module reasoning about it would
 * be a second answer to a legal question that has exactly one.
 *
 * <p>The port returns the decision rather than a boolean alone, because a message
 * that is refused has to be able to say <em>why</em>. "There is no decision on
 * record" and "the customer withdrew on 3 March under policy v4" are the same
 * false and completely different answers to a tenant asking why a customer did
 * not receive something.
 */
public interface ConsentDirectory {

    /**
     * The current decision for one purpose at one scope.
     *
     * @param brandId null for a tenant-wide purpose, set for a brand-specific one
     * @param channel null when the purpose is not channel-specific
     * @return empty when nobody ever asked. Absence is not consent, and the caller
     *         must treat it as withheld rather than as permission
     */
    Optional<ConsentState> consentFor(UUID tenantId, UUID accountId, UUID brandId,
            String purpose, String channel);

    /**
     * What a caller may hold about a decision.
     *
     * <p>No evidence reference and no source: those identify how a specific person
     * was asked, and a module that only needs to know whether to send does not
     * need them.
     */
    record ConsentState(boolean granted, String policyVersion, Instant decidedAt) { }
}
