package uz.horecaos.platform.integration.camel.notification;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One provider delivery status, in the platform's vocabulary (ADR 0146
 * Decision 2, {@code receipt}).
 *
 * @param normalizedStatus one of ADR 0020's six: ACCEPTED, DISPATCHED, DELIVERED,
 *                         READ, FAILED, UNKNOWN
 * @param providerStatus the provider's own word, verbatim
 * @param hardBounce the receiver is blacklisted or unroutable: Decision 6's only
 *                   producer of a suppression
 */
public record ReceiptEvent(
        String providerMessageId,
        String normalizedStatus,
        String providerStatus,
        @Nullable Instant occurredAt,
        boolean hardBounce) {}
