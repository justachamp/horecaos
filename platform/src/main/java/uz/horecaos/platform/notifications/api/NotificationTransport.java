package uz.horecaos.platform.notifications.api;

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
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String channel,
            String providerIdempotencyKey);

    /**
     * Whether a real adapter is present for a channel.
     *
     * <p>Asked by eligibility, so a message on an unwired channel is suppressed
     * with a reason a tenant can read rather than created, resolved, rendered, and
     * then quietly failed at the last step.
     */
    boolean supports(String channel);
}
