package uz.qoida.platform.payments.web.payme;

import java.util.LinkedHashMap;
import java.util.Map;

import uz.qoida.platform.payments.infrastructure.payme.PaymeRpcException;

/**
 * The JSON-RPC envelope Payme reads (ADR 0013).
 *
 * <p>Built as a map rather than as a record because two of its members are
 * mutually exclusive and one of them is optional: a success carries {@code result}
 * and no {@code error}, an error carries {@code error} and no {@code result}, and
 * {@code data} appears only on the errors that have one. A record would have to
 * emit all four and rely on a null-exclusion annotation to hide two of them, which
 * puts the wire shape in an annotation instead of in the code that decides it.
 *
 * <p>Three details of the shape, each of which has a reason:
 *
 * <p><em>{@code jsonrpc} is emitted and never required.</em> The docs' own request
 * table lists only {@code method}, {@code params} and {@code id}, and their worked
 * example carries no {@code jsonrpc} member — so requiring it would reject genuine
 * Payme traffic. Payme's PHP template emits it on responses and Payme accepts that,
 * so emitting it is free and makes the body self-describing in a capture.
 *
 * <p><em>{@code message} is an object.</em> {@code {ru, uz, en}}, never a string.
 * The docs make the object mandatory only for the account range
 * {@code -31050…-31099}, but it is valid everywhere, and the alternative — Payme's
 * own Java template, which can carry a single English string — shows a
 * Russian-speaking payer an English sentence at the checkout.
 *
 * <p><em>{@code id} is echoed, including on an authentication failure.</em> Payme's
 * first sandbox test is a bad credential, and the response it expects carries the
 * request's own id. Null when the body did not parse far enough to find one, which
 * is the only honest answer.
 */
final class PaymeJsonRpc {

    private static final String VERSION = "2.0";

    private PaymeJsonRpc() {
    }

    static Map<String, Object> success(Object requestId, Map<String, Object> result) {
        Map<String, Object> body = envelope(requestId);
        body.put("result", result);
        return body;
    }

    static Map<String, Object> error(Object requestId, PaymeRpcException failure) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", failure.code());
        error.put("message", failure.localised().asMap());
        // Omitted rather than sent as null when there is nothing to name. Payme's
        // PHP template omits it too, and an account error cannot reach here without
        // one: PaymeRpcException refuses to be constructed in that range without a
        // field name.
        failure.data().ifPresent(data -> error.put("data", data));

        Map<String, Object> body = envelope(requestId);
        body.put("error", error);
        return body;
    }

    private static Map<String, Object> envelope(Object requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", VERSION);
        // Present even when null, because a response with no id member at all is a
        // different thing from one that admits it could not read the id.
        body.put("id", requestId);
        return body;
    }
}
