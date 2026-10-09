package uz.horecaos.platform.ordering.domain;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where in the order's life a mark was captured (ADR 0161), derived by the server from the order's status and
 * never chosen by the client: {@link #PICK} while the order is {@code CONFIRMED} or {@code PREPARING},
 * {@link #HANDOVER} once it is {@code READY}.
 */
public enum MarkCaptureStage {
    PICK,
    HANDOVER;

    /** The stage a status implies, or empty for a status in which a mark can no longer be captured or removed. */
    public static Optional<MarkCaptureStage> forStatus(@Nullable OrderStatus status) {
        if (status == OrderStatus.CONFIRMED || status == OrderStatus.PREPARING) {
            return Optional.of(PICK);
        }
        return status == OrderStatus.READY ? Optional.of(HANDOVER) : Optional.empty();
    }
}
