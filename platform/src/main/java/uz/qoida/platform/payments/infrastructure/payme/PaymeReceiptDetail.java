package uz.qoida.platform.payments.infrastructure.payme;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import uz.qoida.platform.payments.domain.FiscalReceiptLine;
import uz.qoida.platform.payments.domain.SomAmount;
import uz.qoida.platform.payments.domain.TiyinAmount;

/**
 * Payme's {@code detail} object: the fiscal receipt, fixed before the customer
 * pays (ADR 0013, ADR 0038).
 *
 * <p>The timing is the opposite of Click's and it is the whole reason there is no
 * shared builder. Click fiscalizes strictly after capture, because
 * {@code submit_items} needs a CLICK {@code payment_id} that does not exist
 * earlier. Payme takes the lines <em>with the checkout</em> and reports the
 * outcome back afterwards through {@code SetFiscalData}. So by the time anyone
 * knows whether the receipt was accepted, these lines are already immutable.
 *
 * <p><strong>{@code price} is the unit price.</strong> Click's {@code Price} is
 * the line total. Same word, a factor of quantity apart, and one helper shared
 * between the two adapters fiscalizes an order at quantity squared times its
 * value. {@link FiscalReceiptLine} carries unit price and quantity separately for
 * exactly this reason, and each adapter derives what its own wire means.
 *
 * <p>Three things this refuses to emit, each for a reason the notes record:
 *
 * <ul>
 * <li><strong>No {@code shipping}.</strong> Payme's shipping object accepts a
 * title and a price and nothing else — no ИКПУ, no package code, no VAT rate. ADR
 * 0038 requires the delivery fee to carry a classification and blocks publication
 * without one, so putting it in {@code shipping} would throw away the one thing a
 * validator rule was written to guarantee. It goes in {@code items} like anything
 * else. Click has no {@code shipping} at all and forces the same answer.</li>
 * <li><strong>No marked goods.</strong> Click has {@code Labels}; Payme has no
 * field for a marking code anywhere. A marked good therefore cannot lawfully be
 * fiscalized through this object, and the honest response is to refuse the receipt
 * rather than emit one that is silently incomplete.</li>
 * <li><strong>No receipt whose lines do not add up to the charge.</strong> The
 * docs never state the arithmetic, so it is enforced here: a receipt that
 * disagrees with the amount charged is a tax problem rather than a bug, and it is
 * discovered at an inspection rather than in a log.</li>
 * </ul>
 */
public final class PaymeReceiptDetail {

    /** Sale. The docs describe this field as "Sale/Return = 0" for both directions. */
    private static final int RECEIPT_TYPE_SALE = 0;

    private PaymeReceiptDetail() {
    }

    /**
     * Builds the object, or explains why this order cannot be fiscalized by Payme.
     *
     * @param total what the customer will actually be charged, in tiyin. The lines
     *              must sum to it
     * @throws PaymeReceiptRefused when the lines cannot lawfully or arithmetically
     *                             be expressed in this object
     */
    public static Map<String, Object> of(List<FiscalReceiptLine> lines, TiyinAmount total) {
        if (lines == null || lines.isEmpty()) {
            throw new PaymeReceiptRefused("NO_RECEIPT_LINES",
                    "A fiscalised Payme cashbox needs item lines, and this document carries none");
        }

        List<Map<String, Object>> items = new ArrayList<>(lines.size());
        long declared = 0L;

        for (FiscalReceiptLine line : lines) {
            if (line.marked()) {
                throw new PaymeReceiptRefused("MARKING_CODES_UNSUPPORTED",
                        "Payme's detail object has no field for a marking code, so a marked good "
                                + "cannot be fiscalized through it: " + line.fiscalName());
            }

            TiyinAmount unitPrice = TiyinAmount.of(line.unitPrice());
            TiyinAmount discount = TiyinAmount.of(
                    line.discount() == null
                            ? new SomAmount(0, line.unitPrice().currency())
                            : line.discount());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", line.fiscalName());
            // The unit price. Not the line total. See the class comment.
            item.put("price", unitPrice.value());
            item.put("count", line.quantity());
            item.put("code", line.mxikCode());
            item.put("package_code", line.packageCode());
            if (line.unitCode() != null) {
                item.put("units", line.unitCode());
            }
            item.put("vat_percent", line.vatPercent());
            if (discount.value() > 0) {
                // Payme's discount is documented as "с учётом количества" — already
                // multiplied out across the line, unlike its price.
                item.put("discount", discount.value());
            }
            items.add(item);

            declared = Math.addExact(declared,
                    Math.subtractExact(
                            Math.multiplyExact(unitPrice.value(), (long) line.quantity()),
                            discount.value()));
        }

        if (declared != total.value()) {
            throw new PaymeReceiptRefused("RECEIPT_DOES_NOT_MATCH_CHARGE",
                    "The receipt lines total " + declared + " tiyin and the charge is "
                            + total.value() + " tiyin");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receipt_type", RECEIPT_TYPE_SALE);
        detail.put("items", items);
        return detail;
    }

    /** The form field's encoding: the JSON document, base64. */
    public static String encode(String detailJson) {
        return Base64.getEncoder().encodeToString(detailJson.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Why a receipt could not be built.
     *
     * <p>Carries a Qoida code rather than a provider one, because the domain never
     * sees a provider code and none of these conditions has a Payme code anyway:
     * Payme would simply have accepted the wrong receipt.
     */
    public static class PaymeReceiptRefused extends RuntimeException {

        private final String code;

        public PaymeReceiptRefused(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
