package uz.horecaos.platform.notifications.application;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch.PushEnvelope;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;

/**
 * What a Web Push adds to a dispatch, and what its outcome does to the subscription (ADR 0182, D14).
 *
 * <p>A seam of its own, as {@link EmailEnvelopes} is, so the dispatch service, which every channel
 * shares, asks one question and learns nothing about subscriptions, keys or click tokens.
 */
public interface PushDispatches {

    /** No push for any message: what a caller that predates ADR 0182 gets. */
    PushDispatches NONE = new PushDispatches() {
        @Override
        public Optional<Resolved> resolve(NotificationRow row, @Nullable UUID coverAssetId, @Nullable String path) {
            return Optional.empty();
        }

        @Override
        public void settled(NotificationRow row, DispatchOutcome outcome) {}
    };

    /**
     * The push service URL and the envelope for this message, minting its click token as a side
     * effect. Empty when the subscription it was addressed to has ended since: the message then cannot
     * be sent, and nothing will bring the subscription back.
     *
     * @param coverAssetId the wording's cover, or null
     * @param path the wording's target path, or null for the storefront's front page
     */
    Optional<Resolved> resolve(NotificationRow row, @Nullable UUID coverAssetId, @Nullable String path);

    /**
     * What the push service said, applied to the subscription: a message it took marks the subscription
     * as alive, and one it says is gone ends it, so the next campaign does not try it.
     */
    void settled(NotificationRow row, DispatchOutcome outcome);

    /** The URL and the envelope. Printing it prints neither. */
    record Resolved(String endpoint, PushEnvelope envelope) {

        @Override
        public String toString() {
            return "Resolved[<redacted>]";
        }
    }
}
