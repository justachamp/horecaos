package uz.horecaos.platform.telemetry.api;

/**
 * How any module tells the operations console that something it owns changed
 * (ADR 0045).
 *
 * <p>Deliberately not the ADR 0004 outbox. A signal has no business meaning, is
 * never replayed, is not a fact anybody reconciles against, and heals at the next
 * resync if it is lost — so writing it into the same transactional outbox that
 * carries {@code OrderConfirmed} would give an ephemeral hint the durability
 * guarantees of a commercial commitment, and put it in the same relay budget. ADR
 * 0032's fourth topic class, {@code {domain}.signals}, exists for exactly this,
 * with seconds of retention and no replay.
 *
 * <p>Publishing is fire and forget and never fails a business transaction. A
 * failed signal costs one screen its acceleration until the client's next poll;
 * a failed order does not.
 */
public interface RealtimeSignalPublisher {

    void publish(RealtimeSignal signal);

    /**
     * Whether a real transport is present.
     *
     * <p>Read by the operations surfaces so a deployment running without Kafka
     * shows its polling fallback honestly rather than appearing to be live.
     */
    default boolean isWired() {
        return true;
    }
}
