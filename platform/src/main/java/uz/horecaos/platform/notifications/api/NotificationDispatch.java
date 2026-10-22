package uz.horecaos.platform.notifications.api;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One rendered message, handed to the route that will send it (ADR 0020,
 * ADR 0007).
 *
 * <p>This is the only object in the system that holds a recipient value and the
 * rendered text at the same time, and it exists for the length of one call. It is
 * built immediately before dispatch from a contact resolved through ADR 0015, and
 * neither field is written to a table, a log, a metric, or an event on either side
 * of the port.
 *
 * <p>{@code providerIdempotencyKey} is stable for the life of one attempt. A retry
 * must carry this exact value: a fresh key would defeat the provider-side
 * deduplication the retry depends on, and the customer would be texted twice.
 *
 * @param locationId null for a message not tied to one location (ADR 0026
 *                   resolves the binding at brand scope in that case)
 * @param recipientValue the phone number or address, resolved for this call only
 * @param subject null on channels that have no subject, which SMS does not
 * @param subjectType what this message is about ({@code notifications.notifications.subject_type}),
 *                     carried through so a channel that tracks live state per
 *                     concern (ADR 0058's Telegram edit-vs-send lifecycle) can key
 *                     on it without a second lookup back into this module
 * @param subjectId the paired identifier, e.g. an order id
 * @param templateKey the semantic message key, which distinguishes two different
 *                     concerns about the same subject (a warning from a
 *                     confirmation) from two updates of the same one
 * @param purpose what the message is for ({@link #PURPOSE_TRANSACTIONAL},
 *                {@link #PURPOSE_MARKETING} or {@link #PURPOSE_COURIER}). ADR 0146: a
 *                gateway account may be allowed to carry sign-in codes and order
 *                messages and not promotions, and the adapter must be able to refuse a
 *                purpose its account has not been cleared for without guessing from the
 *                template key
 * @param email what an email adds to a message and no other channel has (ADR 0182): the
 *              name it is sent in and its way out. Null on every other channel
 */
public record NotificationDispatch(
        UUID notificationId,
        UUID attemptId,
        UUID tenantId,
        UUID brandId,
        @Nullable UUID locationId,
        String channel,
        String recipientValue,
        @Nullable String subject,
        String body,
        String providerIdempotencyKey,
        String correlationId,
        String subjectType,
        UUID subjectId,
        String templateKey,
        String purpose,
        @Nullable EmailEnvelope email) {

    /** An order message, a code, a receipt: anything the customer did not opt into receiving. */
    public static final String PURPOSE_TRANSACTIONAL = "TRANSACTIONAL";

    /** A campaign or an automation: anything that needs a marketing consent decision. */
    public static final String PURPOSE_MARKETING = "MARKETING";

    /** A dispatcher's operational message to a courier: not a customer and not a marketing audience (ADR 0182). */
    public static final String PURPOSE_COURIER = "COURIER";

    /**
     * The sender's name and the recipient's way out, for an email (ADR 0182).
     *
     * @param senderName the brand's display name, shown beside the platform's one address
     * @param unsubscribeUrl the absolute URL a POST of {@code List-Unsubscribe=One-Click}
     *                       unsubscribes the recipient from. Carries an opaque token
     * @param footer the localised sentence appended to the body that points at the same URL,
     *               already rendered, because only the notification side knows the language
     */
    public record EmailEnvelope(String senderName, String unsubscribeUrl, String footer) {

        /** The URL carries a token that unsubscribes a person; nothing here is printed. */
        @Override
        public String toString() {
            return "EmailEnvelope[<redacted>]";
        }
    }

    /**
     * The shape every caller used before ADR 0182: a message with a purpose and no email
     * envelope.
     */
    public NotificationDispatch(
            UUID notificationId,
            UUID attemptId,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String channel,
            String recipientValue,
            @Nullable String subject,
            String body,
            String providerIdempotencyKey,
            String correlationId,
            String subjectType,
            UUID subjectId,
            String templateKey,
            String purpose) {
        this(
                notificationId,
                attemptId,
                tenantId,
                brandId,
                locationId,
                channel,
                recipientValue,
                subject,
                body,
                providerIdempotencyKey,
                correlationId,
                subjectType,
                subjectId,
                templateKey,
                purpose,
                null);
    }

    /**
     * The shape every caller used before ADR 0146 named a purpose. A message that
     * does not say what it is for is transactional, which is the purpose a gateway
     * account is cleared for first.
     */
    public NotificationDispatch(
            UUID notificationId,
            UUID attemptId,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String channel,
            String recipientValue,
            @Nullable String subject,
            String body,
            String providerIdempotencyKey,
            String correlationId,
            String subjectType,
            UUID subjectId,
            String templateKey) {
        this(
                notificationId,
                attemptId,
                tenantId,
                brandId,
                locationId,
                channel,
                recipientValue,
                subject,
                body,
                providerIdempotencyKey,
                correlationId,
                subjectType,
                subjectId,
                templateKey,
                PURPOSE_TRANSACTIONAL,
                null);
    }

    public NotificationDispatch {
        Objects.requireNonNull(notificationId, "A notification id is required");
        Objects.requireNonNull(attemptId, "An attempt id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channel, "A channel is required");
        Objects.requireNonNull(providerIdempotencyKey, "A provider idempotency key is required");
        Objects.requireNonNull(purpose, "A purpose is required");
        if (recipientValue == null || recipientValue.isBlank()) {
            throw new IllegalArgumentException("A dispatch without a recipient cannot be sent");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A dispatch without a body cannot be sent");
        }
    }

    /**
     * Deliberately overridden.
     *
     * <p>A record's generated {@code toString} prints every component, so one
     * incautious log line, one exception message, or one debugger-friendly
     * {@code String.valueOf} would put a customer's phone number and the text they
     * were sent into a log file that ADR 0029 says must never hold either.
     */
    @Override
    public String toString() {
        return "NotificationDispatch[notificationId=%s, attemptId=%s, channel=%s]"
                .formatted(notificationId, attemptId, channel);
    }
}
