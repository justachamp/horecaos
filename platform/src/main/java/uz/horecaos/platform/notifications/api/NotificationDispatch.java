package uz.horecaos.platform.notifications.api;

import java.util.Objects;
import java.util.UUID;

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
 */
public record NotificationDispatch(
        UUID notificationId,
        UUID attemptId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        String channel,
        String recipientValue,
        String subject,
        String body,
        String providerIdempotencyKey,
        String correlationId,
        String subjectType,
        UUID subjectId,
        String templateKey) {

    public NotificationDispatch {
        Objects.requireNonNull(notificationId, "A notification id is required");
        Objects.requireNonNull(attemptId, "An attempt id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channel, "A channel is required");
        Objects.requireNonNull(providerIdempotencyKey, "A provider idempotency key is required");
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
