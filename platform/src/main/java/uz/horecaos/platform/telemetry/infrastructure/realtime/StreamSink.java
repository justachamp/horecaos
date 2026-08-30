package uz.horecaos.platform.telemetry.infrastructure.realtime;

/**
 * Where a frame is written (ADR 0045).
 *
 * <p>An interface rather than {@code SseEmitter} directly, for one reason worth
 * the file: the interesting behaviour of the stream registry — coalescing to a
 * cadence cap, the fifteen-second heartbeat, closing on token expiry, closing on
 * a grants change, refusing past the connection cap — is behaviour nobody should
 * need a servlet container and a real socket to test. Every one of those is a
 * property of what gets written and when, which is exactly what this interface
 * is.
 */
public interface StreamSink {

    /**
     * @param eventName the SSE {@code event:} field — {@code signal},
     *                  {@code snapshot}, or {@code resync}
     * @param id        the SSE {@code id:} field, which a reconnecting client
     *                  sends back as {@code Last-Event-Id}
     */
    void send(String eventName, String id, String data);

    /**
     * A comment frame. Carries no data and exists so a reverse proxy does not
     * idle a quiet connection closed, which is a failure that looks like a
     * network fault rather than like a timeout.
     */
    void heartbeat();

    void complete();

    void completeWithError(Throwable failure);
}
