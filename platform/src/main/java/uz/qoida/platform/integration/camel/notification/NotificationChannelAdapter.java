package uz.qoida.platform.integration.camel.notification;

import uz.qoida.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.notifications.api.NotificationDispatch;

/**
 * One notification provider, behind ADR 0007's rules.
 *
 * <p>An adapter holds no configuration and no credential. Both arrive on the
 * {@link ProviderCall} for the duration of one request, which is what keeps a
 * rotated token from being pinned inside a long-lived bean.
 *
 * <p>An adapter must not throw for a provider failure. Every outcome — refused,
 * rate limited, timed out after sending — comes back as a {@link ProviderOutcome},
 * because the one thing the caller cannot recover afterwards is whether the
 * provider might already have acted.
 */
public interface NotificationChannelAdapter {

    /** The ADR 0026 {@code provider_type} this adapter implements. */
    String providerType();

    /** The ADR 0020 channel it sends on. */
    String channel();

    ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call);

    /**
     * Asks the provider what happened to a request.
     *
     * <p>Always safe to repeat, which is the entire reason it exists: it is what
     * stands between an uncertain send and a duplicate message.
     */
    ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call);
}
