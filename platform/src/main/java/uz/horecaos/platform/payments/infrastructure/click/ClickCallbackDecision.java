package uz.horecaos.platform.payments.infrastructure.click;

import java.util.UUID;

/**
 * What HorecaOS decided about one SHOP API arrival (ADR 0013).
 *
 * <p>Separated from the JSON body the controller writes so that the decision can
 * be made, tested and recorded without a servlet: the check order is the part of
 * this integration that is easy to get wrong and expensive to get wrong, and it is
 * worth being able to assert it directly.
 *
 * @param merchantTransactionId the value HorecaOS returns as {@code merchant_prepare_id}
 *                              on Prepare and as {@code merchant_confirm_id} on
 *                              Complete. One value rather than two because it is a
 *                              deterministic function of the attempt and Click only
 *                              ever echoes it back; null when there is no attempt to
 *                              derive it from
 * @param attemptId             the attempt this arrival was about, for the callback
 *                              inbox. Null when the request never got as far as
 *                              naming one, which is every signature failure
 */
public record ClickCallbackDecision(ClickShopApiError error, Integer merchantTransactionId, UUID attemptId) {

    static ClickCallbackDecision failed(ClickShopApiError error) {
        return new ClickCallbackDecision(error, null, null);
    }

    static ClickCallbackDecision answered(ClickShopApiError error, UUID attemptId, int merchantTransactionId) {
        return new ClickCallbackDecision(error, merchantTransactionId, attemptId);
    }

    public boolean successful() {
        return error.successful();
    }

    /** What the callback inbox records as the answer HorecaOS gave, in Click's vocabulary. */
    public String responseCode() {
        return Integer.toString(error.code());
    }
}
