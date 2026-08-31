package uz.horecaos.platform.payments.web.click;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.payments.infrastructure.click.ClickShopApiError;

/**
 * What HorecaOS answers Click on Prepare and Complete (ADR 0013).
 *
 * <p>Flat, snake_case, and always carried on an HTTP 200. Click documents no
 * status code for these endpoints and both of its reference implementations answer
 * 200 with the error inside the body; a non-200 is an undocumented case that reads
 * as a transport failure and gets retried, and after several retries the payment
 * goes to manual investigation by Click support.
 *
 * <p>{@code merchant_prepare_id} appears on a Prepare answer and
 * {@code merchant_confirm_id} on a Complete answer, and absent fields are omitted
 * rather than sent as null. Both reference implementations return both fields on
 * both calls; Click ignores what it does not expect, so that is harmless rather
 * than correct, and there is no reason to copy it.
 *
 * <p>{@code error_note} is Click's own wording, unchanged. It is what Click's
 * support tooling displays, and a paraphrase makes an argument about a stuck
 * payment harder to settle than it needs to be.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClickShopApiResponse(
        @JsonProperty("click_trans_id") @Nullable String clickTransId,
        @JsonProperty("merchant_trans_id") @Nullable String merchantTransId,
        @JsonProperty("merchant_prepare_id") @Nullable Integer merchantPrepareId,
        @JsonProperty("merchant_confirm_id") @Nullable Integer merchantConfirmId,
        @JsonProperty("error") int error,
        @JsonProperty("error_note") String errorNote) {

    public static ClickShopApiResponse prepared(
            @Nullable String clickTransId, @Nullable String merchantTransId, int merchantPrepareId) {
        return new ClickShopApiResponse(
                clickTransId,
                merchantTransId,
                merchantPrepareId,
                null,
                ClickShopApiError.SUCCESS.code(),
                ClickShopApiError.SUCCESS.note());
    }

    public static ClickShopApiResponse confirmed(
            @Nullable String clickTransId, @Nullable String merchantTransId, int merchantConfirmId) {
        return new ClickShopApiResponse(
                clickTransId,
                merchantTransId,
                null,
                merchantConfirmId,
                ClickShopApiError.SUCCESS.code(),
                ClickShopApiError.SUCCESS.note());
    }

    /**
     * A settled or cancelled transaction answered again.
     *
     * <p>Carries the confirm id with the error code on purpose: {@code -4} means
     * "already paid", which is a success Click understands, and echoing the id it
     * was given the first time keeps a replayed Complete indistinguishable from the
     * original as far as Click's records are concerned.
     */
    public static ClickShopApiResponse settled(
            @Nullable String clickTransId,
            @Nullable String merchantTransId,
            int merchantConfirmId,
            ClickShopApiError error) {
        return new ClickShopApiResponse(
                clickTransId, merchantTransId, null, merchantConfirmId, error.code(), error.note());
    }

    public static ClickShopApiResponse failed(
            @Nullable String clickTransId, @Nullable String merchantTransId, ClickShopApiError error) {
        return new ClickShopApiResponse(clickTransId, merchantTransId, null, null, error.code(), error.note());
    }
}
