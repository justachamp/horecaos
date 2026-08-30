package uz.horecaos.platform.integration.camel.sms;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import uz.horecaos.platform.customers.spi.VerificationCodeTransport.VerificationMessage;

/**
 * One verification-code command carried through the ADR 0007 route.
 *
 * <p>Two shapes in one type, exactly as {@code NotificationSendOperation} carries
 * a send and a status query: {@link Kind#SEND} puts a message on the wire, and
 * {@link Kind#RESOLVE} asks the provider what it already has. They share a route,
 * a binding resolution and a credential, and splitting them would duplicate all
 * three.
 *
 * <p>The resolve shape carries the <em>code</em> as well as the destination, and
 * that is the design rather than an oversight. This provider has no idempotency
 * key and {@code /search} answers by destination and day, so the only way to tell
 * our lost message apart from the other messages that number received today is to
 * look for the one whose text is this code. The comparison happens in memory,
 * inside {@link VasSmsGatewayAdapter}, and neither side of it is ever written
 * down.
 *
 * <p>{@code toString} is overridden for the reason {@link VerificationMessage}
 * gives on its own: Camel prints exchange bodies into route logs and into the
 * messages of the exceptions it wraps, and a generated one here would put a
 * customer's phone number and a live one-time code into both.
 */
public record SmsVerificationOperation(
        Kind kind,
        UUID tenantId,
        UUID brandId,
        UUID challengeId,
        String destination,
        String code,
        String text,
        Instant issuedAt) {

    public enum Kind { SEND, RESOLVE }

    public SmsVerificationOperation {
        Objects.requireNonNull(kind, "A kind is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(brandId, "A brand id is required");
        Objects.requireNonNull(challengeId, "A challenge id is required");
        Objects.requireNonNull(issuedAt, "An issue instant is required");
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("A message without a destination cannot be sent");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A verification message without a code is not one");
        }
        if (kind == Kind.SEND && (text == null || text.isBlank())) {
            throw new IllegalArgumentException("A send needs rendered text");
        }
    }

    static SmsVerificationOperation send(VerificationMessage message, String text) {
        return new SmsVerificationOperation(Kind.SEND, message.tenantId(), message.brandId(),
                message.challengeId(), message.destination(), message.code(), text,
                message.issuedAt());
    }

    /** The same message, asked about rather than sent. Never sends anything. */
    SmsVerificationOperation resolving() {
        return new SmsVerificationOperation(Kind.RESOLVE, tenantId, brandId, challengeId,
                destination, code, text, issuedAt);
    }

    @Override
    public String toString() {
        return "SmsVerificationOperation[kind=%s, challengeId=%s]".formatted(kind, challengeId);
    }
}
