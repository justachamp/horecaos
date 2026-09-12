package uz.horecaos.platform.iam.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Whether a tenant's commercial plan entitles a named feature — the ADR 0021
 * question, read from inside {@code iam} without {@code iam} depending on the
 * {@code commercial} module.
 *
 * <p>{@code commercial} already depends on {@code iam.api} throughout (every
 * capability annotation on every commercial-module controller); a reference
 * the other way would make the two modules cyclic. This port is owned here and
 * implemented in {@code commercial}, the same seam {@code
 * customers.spi.CustomerErasureParticipant} uses for the identical shape of
 * problem: the consumer declares what it needs, the module that can answer
 * implements it, and Spring wires the one bean that exists at runtime.
 *
 * <p>The only consumer today is Staff 9.5's access check (ADR 0109): {@code
 * ErrorCode.ENTITLEMENT_REQUIRED} versus {@code INSUFFICIENT_CAPABILITY} is a
 * distinction {@link AuthorizationService} alone cannot draw, because
 * entitlement is a separate, independent check from capability (see that
 * interface's own doc).
 */
public interface EntitlementGate {

    /**
     * @param entitlementKeyCode a code from {@code commercial.api.EntitlementKeys}
     * @return empty when the code names no known key, or a key that is not a
     *         plain feature toggle (a counted limit answers a different
     *         question — "how many are left", not "is this on")
     */
    Optional<Answer> checkFeature(UUID tenantId, String entitlementKeyCode);

    /**
     * @param entitled    whether the tenant's plan includes this feature right now
     * @param description what the key means, for the answer sentence
     * @param upgradePath where a merchant goes to buy it
     */
    record Answer(boolean entitled, String description, String upgradePath) {}
}
