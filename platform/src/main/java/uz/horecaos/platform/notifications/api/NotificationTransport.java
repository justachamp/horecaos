package uz.horecaos.platform.notifications.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The seam between this module and the provider (ADR 0020, ADR 0007).
 *
 * <p>Notifications never opens a socket. ADR 0007 keeps every provider call behind
 * a Camel route that owns bounded redelivery, circuit breaking, dead-lettering
 * into the ADR 0006 failure model, and the rule that an uncertain outcome
 * reconciles rather than repeats — and it keeps Camel out of domain modules, which
 * {@code ModularArchitectureTests} enforces. This interface is what those two
 * rules leave: the domain names the send, the integration module performs it.
 *
 * <p>Implementations must not throw for a provider failure. Every failure arrives
 * as a {@link DispatchOutcome}, because the difference between "not sent" and
 * "possibly sent" is the whole decision this module makes next, and an exception
 * erases it.
 */
public interface NotificationTransport {

    /** Sends one rendered message. Never called inside a business transaction. */
    DispatchOutcome dispatch(NotificationDispatch dispatch);

    /**
     * Discovers what actually happened after an uncertain outcome.
     *
     * <p>A query, so it is always safe to repeat. This is what stands between an
     * uncertain send and a duplicate one, and it is the reason the uncertain case
     * exists as a status rather than as a retry.
     *
     * <p>The brand and location travel with it because the provider account is
     * bound at one of those scopes: asking at the tenant alone resolves nothing and
     * would leave every uncertain message stuck in uncertainty forever.
     */
    DispatchOutcome reconcile(
            UUID tenantId, UUID brandId, @Nullable UUID locationId, String channel, String providerIdempotencyKey);

    /**
     * Discovers what actually happened, given everything the platform knows about
     * the attempt (ADR 0146 Decision 2, {@code resolve}).
     *
     * <p>The key-only overload above is enough for a gateway that holds our
     * idempotency key. One that does not (VAS documents none) can be asked only
     * "what did you send to this number today", which needs the provider's own
     * message id when we have one and otherwise the destination and the hash of
     * what we sent. Defaulted to the key-only call so a transport that predates
     * ADR 0146 keeps its meaning.
     */
    default DispatchOutcome reconcile(ReconcileRequest request) {
        return reconcile(
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.channel(),
                request.providerIdempotencyKey());
    }

    /**
     * Whether a message for {@code purpose} can leave on {@code channel} for this
     * brand right now, and if not, the stable reason (ADR 0146 Decision 8).
     *
     * <p>"Wired" means a working account for this brand, not an adapter in this
     * build: an active binding whose provider type has an adapter, whose sender is
     * configured, and whose account has been cleared for the purpose. Defaulted to
     * the global answer for a transport with no scoped notion of readiness.
     */
    default Readiness readiness(UUID tenantId, UUID brandId, String channel, String purpose) {
        return supports(channel) ? Readiness.ok() : Readiness.notReady("NO_ADAPTER");
    }

    /**
     * Whether a real adapter is present for a channel.
     *
     * <p>Asked by eligibility, so a message on an unwired channel is suppressed
     * with a reason a tenant can read rather than created, resolved, rendered, and
     * then quietly failed at the last step.
     */
    boolean supports(String channel);

    /**
     * Everything the platform has about one attempt whose outcome is not known.
     *
     * @param externalMessageId the provider's id when the answer to the send gave
     *                          one, null when it never arrived
     * @param destination the recipient, resolved for this call only and never
     *                    persisted (ADR 0029); null when {@code externalMessageId}
     *                    already identifies the message, because every
     *                    destination lookup decrypts a number
     * @param renderedContentHash SHA-256 of the text that was sent, for a
     *                            gateway that can only be searched by day and
     *                            destination
     * @param requestedAt when the attempt was made, which names the day to search
     */
    record ReconcileRequest(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String channel,
            String providerIdempotencyKey,
            @Nullable String externalMessageId,
            @Nullable String destination,
            @Nullable String renderedContentHash,
            Instant requestedAt) {

        /** Holds a destination: Camel prints exchange bodies into logs, and a record prints its components. */
        @Override
        public String toString() {
            return "ReconcileRequest[channel=%s, key=%s]".formatted(channel, providerIdempotencyKey);
        }
    }

    /**
     * @param reason a stable code when not ready: {@code NO_ADAPTER},
     *               {@code NO_PROVIDER_BINDING}, {@code INSTALLATION_INACTIVE},
     *               {@code SMS_ACCOUNT_MISCONFIGURED} or
     *               {@code SMS_PURPOSE_NOT_PERMITTED}
     */
    record Readiness(boolean ready, @Nullable String reason) {

        public static Readiness ok() {
            return new Readiness(true, null);
        }

        public static Readiness notReady(String reason) {
            return new Readiness(false, reason);
        }
    }
}
