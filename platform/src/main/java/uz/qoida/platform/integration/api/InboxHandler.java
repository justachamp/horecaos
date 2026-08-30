package uz.qoida.platform.integration.api;

/**
 * A named consumer of one event type and version (ADR 0005).
 *
 * <p>Handlers are registered explicitly by {@code (eventType, eventVersion)}.
 * There is deliberately no polymorphic dispatch on a class name taken from
 * incoming JSON: that turns a message broker into a remote code loader.
 *
 * <p>A handler must not call an external provider. Its work commits with the
 * inbox transition, and a network call inside that transaction would hold a
 * database transaction open across the network and could not be rolled back.
 * Provider work goes to the outbox instead.
 *
 * @param <T> the version-specific payload type
 */
public interface InboxHandler<T> {

    /** Stable across restarts and deployments; it is half the deduplication key. */
    String consumerName();

    String eventType();

    int eventVersion();

    Class<T> payloadType();

    void handle(ExternalEventEnvelope<T> event);
}
