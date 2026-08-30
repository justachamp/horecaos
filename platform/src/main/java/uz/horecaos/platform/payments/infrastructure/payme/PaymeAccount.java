package uz.horecaos.platform.payments.infrastructure.payme;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * HorecaOS's Payme {@code account} schema (ADR 0013).
 *
 * <p>One field, {@code order_id}, carrying the attempt's {@code merchant_trans_id}
 * — an opaque, non-sequential value HorecaOS minted before the checkout link was
 * built. Two properties of that choice are load-bearing.
 *
 * <p><em>It is frozen.</em> The field name is configured on the cashbox and baked
 * into every checkout link ever emitted; changing it retroactively breaks
 * in-flight payments and every link a customer has open. It is a constant here so
 * that it cannot be spelled differently in two places.
 *
 * <p><em>It is unguessable.</em> {@code CheckPerformTransaction} is reachable from
 * Payme's checkout page and is unauthenticated from the customer's side, so a
 * sequential integer would let anyone walk other customers' orders and learn their
 * totals. A UUID without dashes is what {@code PaymentAttemptService} mints.
 *
 * <p>Values arrive as strings from the GET and POST checkout surfaces and are
 * loosely typed thereafter — Payme's own Java template types the order as a
 * {@code Long} and its PHP template multiplies a string by one. Neither is safe:
 * this reads whatever arrived as text and validates the shape.
 */
public final class PaymeAccount {

    /** Frozen. See the class comment. */
    public static final String ORDER_FIELD = "order_id";

    /** {@code merchant_trans_id} is a dashless UUID, and nothing else is accepted. */
    private static final int REFERENCE_LENGTH = 32;

    private PaymeAccount() {}

    /**
     * Reads the order reference out of {@code params.account}.
     *
     * @throws PaymeRpcException in the account range, with {@code data} naming the
     *                           field, which is what the docs require of every
     *                           error between -31050 and -31099
     */
    public static String orderReference(JsonNode params) {
        JsonNode account = params.path("account");
        if (!account.isObject()) {
            throw PaymeErrors.accountFieldMissing();
        }

        JsonNode value = account.path(ORDER_FIELD);
        if (value.isMissingNode() || value.isNull()) {
            throw PaymeErrors.accountFieldMissing();
        }

        String reference = value.asString("").strip();
        if (reference.isEmpty()) {
            throw PaymeErrors.accountFieldMissing();
        }

        // Shape-checked before it reaches a query. A value of the wrong shape can
        // only be a link somebody hand-edited, and answering "incorrect order code"
        // is both true and the same answer an unknown order gets — which is what
        // stops this endpoint being an oracle for which order references exist.
        if (reference.length() != REFERENCE_LENGTH || !isHexadecimal(reference)) {
            throw PaymeErrors.orderNotFound();
        }
        return reference;
    }

    /**
     * The account object as it must be reproduced in {@code GetStatement}.
     *
     * <p>Payme matches statement rows back to its own receipts by this object, so
     * it has to be the same shape that arrived.
     */
    public static Map<String, Object> of(String merchantTransId) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put(ORDER_FIELD, merchantTransId);
        return account;
    }

    private static boolean isHexadecimal(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hexadecimal = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!hexadecimal) {
                return false;
            }
        }
        return true;
    }
}
