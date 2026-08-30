package uz.qoida.platform.payments.web.click;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

import uz.qoida.platform.payments.infrastructure.click.ClickCallbackDecision;
import uz.qoida.platform.payments.infrastructure.click.ClickCallbackProcessor;
import uz.qoida.platform.payments.infrastructure.click.ClickCallbackRequest;
import uz.qoida.platform.payments.infrastructure.click.ClickShopApiError;

/**
 * Click's SHOP API endpoints (ADR 0013).
 *
 * <p>Two form-encoded POSTs, both answered HTTP 200 with the outcome inside the
 * JSON body. The status is 200 even for {@code -1 SIGN CHECK FAILED!}: Click
 * documents no status code for these calls, both of its reference implementations
 * answer 200, and anything else is read as a transport failure and retried until
 * the payment goes to manual investigation by Click support.
 *
 * <p>The binding is in the path because the credential identifies the account —
 * Click's {@code secret_key} is per service — so a single shared callback URL could
 * not tell one restaurant's service from another's. The segment is not a secret and
 * is guessable by design; the MD5 signature is what authenticates the request, and
 * it is verified before any database is touched.
 *
 * <p>ADR 0031's conventions deliberately do not apply here and cannot: the wire
 * format is Click's down to the content type, there is no idempotency key, no
 * expected version and no Problem Details, and a failure still has to be an HTTP
 * 200 a Click parser understands. The exemption is recorded in ADR 0013 so that it
 * is a decision rather than a violation somebody finds later.
 *
 * <p>Hidden from the published OpenAPI document: this is Click's contract, not
 * Qoida's, and publishing it would invite a client to call it.
 */
@RestController
@RequestMapping("/providers/click/{bindingRef}")
@Hidden
public class ClickShopApiController {

    private static final Logger log = LoggerFactory.getLogger(ClickShopApiController.class);

    private final ClickCallbackProcessor callbacks;

    public ClickShopApiController(ClickCallbackProcessor callbacks) {
        this.callbacks = callbacks;
    }

    /** {@code action=0}: verify the order and the amount, reserve, and mint the prepare id. */
    @PostMapping(path = "/prepare", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClickShopApiResponse prepare(@PathVariable String bindingRef,
            @RequestParam Map<String, String> form) {
        return answer(bindingRef, ClickCallbackRequest.ACTION_PREPARE, form);
    }

    /**
     * {@code action=1}: the only Click surface that credits an order.
     *
     * <p>Never answers a business failure. After a successful charge the permitted
     * responses are {@code 0}, {@code -4} and {@code -9}; an order that can no longer
     * be fulfilled is credited, answered {@code 0}, and reversed.
     */
    @PostMapping(path = "/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClickShopApiResponse complete(@PathVariable String bindingRef,
            @RequestParam Map<String, String> form) {
        return answer(bindingRef, ClickCallbackRequest.ACTION_COMPLETE, form);
    }

    private ClickShopApiResponse answer(String bindingRef, String action,
            Map<String, String> form) {

        String clickTransId = form.get("click_trans_id");
        String merchantTransId = form.get("merchant_trans_id");
        boolean completing = ClickCallbackRequest.ACTION_COMPLETE.equals(action);

        ClickCallbackDecision decision;
        try {
            decision = callbacks.handle(bindingRef, action, form);
        } catch (RuntimeException failure) {
            // Nothing was decided: the credit runs in a database transaction that
            // has rolled back, so no order has been changed and no business answer
            // has been given. -7 is the one code Click documents as "transient, come
            // back", and it is returned only here, where it carries no decision.
            // Reaching it after a successful charge is survivable — Click retries,
            // and the retry credits the order — whereas answering -2 or -5 in this
            // situation would leave the customer charged and uncredited.
            log.error("A Click {} could not be processed; answering -7 so Click retries.",
                    action, failure);
            return ClickShopApiResponse.failed(clickTransId, merchantTransId,
                    ClickShopApiError.FAILED_TO_UPDATE_USER);
        }

        if (!decision.successful()) {
            return decision.merchantTransactionId() == null
                    ? ClickShopApiResponse.failed(clickTransId, merchantTransId, decision.error())
                    // -4 and -9 carry the id the transaction was given the first
                    // time, so that a replayed Complete is indistinguishable from
                    // the original in Click's own records.
                    : ClickShopApiResponse.settled(clickTransId, merchantTransId,
                            decision.merchantTransactionId(), decision.error());
        }

        return completing
                ? ClickShopApiResponse.confirmed(clickTransId, merchantTransId,
                        decision.merchantTransactionId())
                : ClickShopApiResponse.prepared(clickTransId, merchantTransId,
                        decision.merchantTransactionId());
    }
}
