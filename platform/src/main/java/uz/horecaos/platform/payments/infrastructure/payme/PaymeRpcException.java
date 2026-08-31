package uz.horecaos.platform.payments.infrastructure.payme;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A JSON-RPC error on its way to a Payme response body (ADR 0013).
 *
 * <p>Unchecked, and thrown from anywhere in the handler chain, because the
 * alternative — threading an {@code Either} through seven methods — puts the
 * business decision and the wire format in the same expression and makes both
 * harder to read. The controller converts one of these into an
 * <strong>HTTP 200</strong> body with an {@code error} member, which is the only
 * shape Payme accepts.
 *
 * <p>It carries no stack trace. These are business answers, not faults: an order
 * that is already paid is Payme working correctly, and filling in a stack trace
 * for every one of them costs measurably on a path with a sub-second budget.
 */
public class PaymeRpcException extends RuntimeException {

    private final int code;
    private final PaymeMessage localised;
    private final @Nullable String data;

    public PaymeRpcException(int code, PaymeMessage localised, @Nullable String data) {
        super(
                Objects.requireNonNull(localised, "A localised message is required")
                        .en(),
                null,
                false,
                false);
        this.code = code;
        this.localised = localised;
        this.data = data;

        if (PaymeErrorCode.isAccountError(code) && data == null) {
            // The docs state it four separate times: in -31050..-31099, `data`
            // must name the offending account sub-field. An account error without
            // it is a response Payme's validator is entitled to reject, and the
            // payer sees a blank field name in the checkout.
            throw new IllegalArgumentException("An account error must name the offending account field in data");
        }
    }

    public PaymeRpcException(int code, PaymeMessage localised) {
        this(code, localised, null);
    }

    public int code() {
        return code;
    }

    public PaymeMessage localised() {
        return localised;
    }

    public Optional<String> data() {
        return Optional.ofNullable(data);
    }
}
