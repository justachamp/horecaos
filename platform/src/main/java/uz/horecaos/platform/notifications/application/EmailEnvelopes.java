package uz.horecaos.platform.notifications.application;

import java.util.Optional;
import uz.horecaos.platform.notifications.api.NotificationDispatch.EmailEnvelope;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;

/**
 * What an email adds to a dispatch (ADR 0182): the name it is sent in and its way out.
 *
 * <p>A seam of its own so the dispatch service, which every other channel shares, asks one question
 * and learns nothing about tokens, brands or footers.
 */
public interface EmailEnvelopes {

    /** No envelope for any message: what a caller that predates ADR 0182 gets. */
    EmailEnvelopes NONE = row -> Optional.empty();

    /**
     * The envelope for this message, minting its unsubscribe token as a side effect. Empty for a
     * message that is not an email, or whose customer cannot be named (it then cannot be sent).
     */
    Optional<EmailEnvelope> forMessage(NotificationRow row);
}
