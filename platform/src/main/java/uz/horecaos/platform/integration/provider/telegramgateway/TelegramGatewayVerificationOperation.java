package uz.horecaos.platform.integration.provider.telegramgateway;

import java.util.Objects;
import java.util.UUID;

/**
 * One {@code sendVerificationMessage} call, for the length of one call (ADR 0063).
 *
 * <p>{@code SmsVerificationOperation}'s own shape, minus the fields Telegram
 * Gateway's API has no use for: there is no {@code text} to render, because
 * the Gateway composes its own delivery message around the code, and there
 * is no {@code kind} to distinguish send from resolve, because this product
 * offers no equivalent of {@code /search} — {@code checkVerificationStatus}
 * answers by {@code request_id}, which is exactly the value a successful
 * send already returns, so there is nothing here to reconcile against by
 * destination and day the way the SMS side must.
 */
public record TelegramGatewayVerificationOperation(UUID tenantId, UUID challengeId, String destination, String code) {

    public TelegramGatewayVerificationOperation {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(challengeId, "A challenge id is required");
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("A message without a destination cannot be sent");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A verification message without a code is not one");
        }
    }

    /**
     * Deliberately overridden, the same reason every sibling operation type gives:
     * a generated {@code toString} would print the destination and the live code.
     */
    @Override
    public String toString() {
        return "TelegramGatewayVerificationOperation[challengeId=%s]".formatted(challengeId);
    }
}
