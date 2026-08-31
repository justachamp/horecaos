package uz.horecaos.platform.payments.infrastructure.click.fake;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.infrastructure.click.ClickCallbackDecision;
import uz.horecaos.platform.payments.infrastructure.click.ClickCallbackProcessor;
import uz.horecaos.platform.payments.infrastructure.click.ClickCallbackRequest;
import uz.horecaos.platform.payments.infrastructure.click.ClickPrepareId;
import uz.horecaos.platform.payments.infrastructure.click.ClickSignature;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * The simulated customer, for CLICK, under the {@code local} profile only (ADR
 * 0007, ADR 0013).
 *
 * <p>Everything a real Click customer's phone would prove is proven here the same
 * way: a correctly signed {@code prepare} followed by a correctly signed {@code
 * complete}, posted to the same {@link ClickCallbackProcessor} the real SHOP API
 * controller calls — {@code ClickShopApiController.prepare}/{@code .complete} are a
 * thin DTO shim over exactly this method, per {@code ClickShopApiCallbackTests}'
 * own precedent of calling the processor directly. Signature verification,
 * amount enforcement, the attempt state machine, the {@code PaymentCaptured} event
 * and everything downstream of it run for real; only the phone is fake.
 *
 * <p><strong>Never reachable outside a developer's own laptop.</strong> {@code
 * @Profile("local")} means the bean, and therefore every method on it, does not
 * exist unless the {@code local} profile is active — the same guard {@code
 * FakeClickProviderConfiguration} and {@code PresetVerificationCodeSource} use. It
 * is exposed over HTTP by exactly one controller, {@code
 * DevFakeClickPaymentController}, itself profile-gated, {@code @Hidden} from the
 * published OpenAPI contract, and secured by its own {@code local}-only filter
 * chain — the same three-lock shape.
 *
 * <p>Resolves the secret Click itself would already know — the plaintext behind the
 * binding's {@code secretReference}, through the real ADR 0028 {@link
 * SecretResolver} — because that secret is exactly what proves a signature
 * genuine; a fake customer who could not compute it would not be testing the
 * signature check at all.
 */
@Service
@Profile("local")
public class FakeCustomerPaymentService {

    private static final DateTimeFormatter SIGN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final PaymentBindingResolver bindings;
    private final SecretResolver secrets;
    private final ClickCallbackProcessor callbacks;
    private final ObjectProvider<FakeClickHttpProvider> fakeProvider;
    private final Clock clock;

    public FakeCustomerPaymentService(
            JdbcPaymentIntentStore intents,
            JdbcPaymentAttemptStore attempts,
            PaymentBindingResolver bindings,
            SecretResolver secrets,
            ClickCallbackProcessor callbacks,
            ObjectProvider<FakeClickHttpProvider> fakeProvider,
            Clock clock) {
        this.intents = intents;
        this.attempts = attempts;
        this.bindings = bindings;
        this.secrets = secrets;
        this.callbacks = callbacks;
        this.fakeProvider = fakeProvider;
        this.clock = clock;
    }

    /** What the fake customer's Click app would carry away from a successful payment. */
    public record FakePaymentResult(
            UUID attemptId, String clickTransId, String clickPaydocId, String merchantTransId, String paymentId) {}

    public static final class NoPayableClickAttemptException extends RuntimeException {
        public NoPayableClickAttemptException(String message) {
            super(message);
        }
    }

    /**
     * Pays whichever CLICK attempt is currently open against an order's live
     * intent, exactly as {@code checkout.openOrRePresent} would have left one.
     */
    public FakePaymentResult payOpenClickAttempt(UUID tenantId, UUID orderId) {
        PaymentIntent intent = intents.findLiveForOrder(tenantId, orderId)
                .orElseThrow(() -> new NoPayableClickAttemptException("No live payment intent for order " + orderId));
        if (intent.providerType() != PaymentProviderType.CLICK) {
            throw new NoPayableClickAttemptException(
                    "Order " + orderId + "'s intent is " + intent.providerType() + ", not CLICK");
        }
        PaymentAttempt attempt = attempts.findOpenForIntent(tenantId, intent.id())
                .orElseThrow(() -> new NoPayableClickAttemptException(
                        "No open payment attempt for order " + orderId + "; open a payment session first"));

        UUID legalEntityId = intent.legalEntityId();
        if (legalEntityId == null) {
            // Mirrors PaymentCheckoutService.openOrRePresent's own SELLER_UNRESOLVED
            // refusal: a real checkout could never have reached a payable attempt
            // without ADR 0038's seller assignment, so the fake customer should not
            // be able to pay one either.
            throw new NoPayableClickAttemptException("No legal entity is assigned to order " + orderId + "'s location");
        }

        ProviderBinding binding = bindings.resolve(
                        tenantId, legalEntityId, PaymentProviderType.CLICK, attempt.businessDate())
                .orElseThrow(() -> new NoPayableClickAttemptException(
                        "No active CLICK merchant binding for legal entity " + legalEntityId));

        return pay(binding, attempt);
    }

    private FakePaymentResult pay(ProviderBinding binding, PaymentAttempt attempt) {
        String secret = secrets.resolve(binding.secretReference()).reveal();
        String serviceId = binding.merchantAccountReference();
        String merchantTransId = attempt.merchantTransId();
        String clickTransId = randomDigits(9);
        String clickPaydocId = randomDigits(9);
        String amount = attempt.amount().value() + ".00";
        String signTime = SIGN_TIME.format(clock.instant().atZone(ZoneOffset.UTC));

        Map<String, String> prepareForm = baseForm(
                clickTransId,
                serviceId,
                clickPaydocId,
                merchantTransId,
                amount,
                ClickCallbackRequest.ACTION_PREPARE,
                signTime);
        prepareForm.put(
                "sign_string",
                ClickSignature.prepare(
                        secret,
                        clickTransId,
                        serviceId,
                        merchantTransId,
                        amount,
                        ClickCallbackRequest.ACTION_PREPARE,
                        signTime));

        ClickCallbackDecision prepared =
                callbacks.handle(binding.callbackPathSegment(), ClickCallbackRequest.ACTION_PREPARE, prepareForm);
        if (!prepared.successful()) {
            throw new NoPayableClickAttemptException("The fake Click prepare was refused: " + prepared.error());
        }

        String prepareId = Integer.toString(ClickPrepareId.forAttempt(attempt.id()));
        Map<String, String> completeForm = baseForm(
                clickTransId,
                serviceId,
                clickPaydocId,
                merchantTransId,
                amount,
                ClickCallbackRequest.ACTION_COMPLETE,
                signTime);
        completeForm.put("merchant_prepare_id", prepareId);
        completeForm.put(
                "sign_string",
                ClickSignature.complete(
                        secret,
                        clickTransId,
                        serviceId,
                        merchantTransId,
                        prepareId,
                        amount,
                        ClickCallbackRequest.ACTION_COMPLETE,
                        signTime));

        ClickCallbackDecision completed =
                callbacks.handle(binding.callbackPathSegment(), ClickCallbackRequest.ACTION_COMPLETE, completeForm);
        if (!completed.successful()) {
            throw new NoPayableClickAttemptException("The fake Click complete was refused: " + completed.error());
        }
        UUID completedAttemptId = completed.attemptId();
        if (completedAttemptId == null) {
            // ClickCallbackDecision's own contract: attemptId is null only when the
            // request never got as far as naming one, which is every signature
            // failure — already ruled out by the successful() check above.
            throw new NoPayableClickAttemptException("The fake Click complete succeeded but named no attempt");
        }

        // The synthetic payment_id fiscalization keys on. A real Click callback
        // never carries one (ClickCallbackProcessor.complete records neither
        // click_trans_id nor click_paydoc_id as the attempt's external payment id —
        // see ClickFiscalAdapter's own resolution), so a fake customer who is
        // meant to unblock fiscalization has to mint one the way Click itself
        // would, on the outbound MERCHANT API surface rather than the inbound one
        // this method just used.
        String paymentId = fakeProvider.stream()
                .findFirst()
                .map(provider -> provider.registerCapturedPayment(
                        serviceId, merchantTransId, attempt.amount().value()))
                .orElseGet(() -> randomDigits(9));

        return new FakePaymentResult(completedAttemptId, clickTransId, clickPaydocId, merchantTransId, paymentId);
    }

    private static Map<String, String> baseForm(
            String clickTransId,
            String serviceId,
            String clickPaydocId,
            String merchantTransId,
            String amount,
            String action,
            String signTime) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("click_trans_id", clickTransId);
        form.put("service_id", serviceId);
        form.put("click_paydoc_id", clickPaydocId);
        form.put("merchant_trans_id", merchantTransId);
        form.put("amount", amount);
        form.put("action", action);
        form.put("error", "0");
        form.put("error_note", "Success");
        form.put("sign_time", signTime);
        return form;
    }

    private static String randomDigits(int count) {
        StringBuilder digits = new StringBuilder(count);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        digits.append(1 + random.nextInt(9));
        for (int i = 1; i < count; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.toString();
    }
}
