package uz.horecaos.platform.notifications.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where a gateway's delivery receipt lands (ADR 0146 Decision 4).
 *
 * <p>Declared here and called by the integration module's receipt endpoint, the
 * same direction as {@link NotificationTransport}: the endpoint knows how to
 * authenticate a provider and read its body, and this module owns the attempt the
 * receipt is about. What crosses is a provider message id and a status, never a
 * number and never text.
 *
 * <p>A receipt is <em>evidence about something this platform already sent</em>. It
 * is applied only to an attempt made under the installation it arrived on, it only
 * ever advances that attempt, and an id nobody here sent creates nothing.
 */
public interface DeliveryReceiptPort {

    /**
     * Applies one receipt.
     *
     * @return what happened to it, so the caller can count it. Never throws for a
     *         receipt that matches nothing: an unknown id is the ordinary answer to
     *         a forged or misrouted callback
     */
    ReceiptDisposition apply(ReceiptCommand receipt);

    /**
     * One provider status for one provider message.
     *
     * @param installationId the ADR 0026 installation the callback arrived on, which
     *                       is the only proof of which provider account it concerns
     * @param bindingIds the bindings of that installation: an attempt belongs to
     *                   this callback only if it was made through one of them
     * @param providerMessageId the id the gateway gave on send
     * @param normalizedStatus ADR 0020's ladder: ACCEPTED, DISPATCHED, DELIVERED,
     *                         READ, FAILED or UNKNOWN
     * @param providerStatus the provider's own word, kept verbatim for support
     * @param occurredAt when the provider says it happened, null when it did not say
     * @param hardBounce the receiver is blacklisted or unroutable (Decision 6)
     */
    record ReceiptCommand(
            UUID tenantId,
            UUID installationId,
            Set<UUID> bindingIds,
            String providerMessageId,
            String normalizedStatus,
            @Nullable String providerStatus,
            @Nullable Instant occurredAt,
            boolean hardBounce) {

        public ReceiptCommand {
            bindingIds = Set.copyOf(bindingIds);
        }

        /** A provider message id is the gateway's, and is not worth printing next to a tenant. */
        @Override
        public String toString() {
            return "ReceiptCommand[installationId=%s, normalizedStatus=%s]".formatted(installationId, normalizedStatus);
        }
    }

    /** What became of a receipt. The names are the metric's bounded outcome tag. */
    enum ReceiptDisposition {
        APPLIED("applied"),
        DUPLICATE("duplicate"),
        UNKNOWN_MESSAGE("unknown_message"),
        REGRESSED("regressed");

        private final String tag;

        ReceiptDisposition(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
