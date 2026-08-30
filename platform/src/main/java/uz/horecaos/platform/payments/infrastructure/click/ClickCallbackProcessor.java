package uz.horecaos.platform.payments.infrastructure.click;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.payments.application.PaymentAttemptService;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.domain.CallbackKind;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTransactionType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderEvidence;
import uz.horecaos.platform.payments.domain.ProviderOutcome;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.RotationAwareSecrets;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcProviderCallbackStore;

/**
 * Prepare and Complete, decided (ADR 0013).
 *
 * <p>This is the only Click surface that credits an order. Everything outbound
 * initiates, queries, reverses or fiscalizes; the redirect is a browser event; and
 * a customer arriving back at {@code return_url} has said nothing at all. Money is
 * learned about here, from an unauthenticated form post whose only authentication
 * is an MD5 over a secret-prefixed concatenation.
 *
 * <p><strong>The check order is the documented one</strong>, which is the PHP
 * reference's with one deliberate change:
 *
 * <ol>
 *   <li>{@code -8} a missing field — for a <em>partially</em> missing body, which
 *       the Django reference gets backwards;</li>
 *   <li>{@code -1} the signature, before any database is touched;</li>
 *   <li>{@code -3} an action that is neither {@code 0} nor {@code 1}, or one that
 *       does not match the endpoint it arrived on;</li>
 *   <li>{@code -8} again for a {@code service_id} that is not this binding's;</li>
 *   <li>{@code -5} no attempt under this {@code merchant_trans_id};</li>
 *   <li>{@code -6} a {@code merchant_prepare_id} this attempt was never told
 *       (Complete only);</li>
 *   <li>{@code -4} already paid — <strong>before</strong> the amount check, so a
 *       replayed Complete for a credited order reports "settled" rather than
 *       tripping an amount comparison against a total that has since been
 *       adjusted;</li>
 *   <li>{@code -9} when Click's own {@code error} arrived negative, which means
 *       Click's side failed and the payment is voided here;</li>
 *   <li>{@code -2} an amount that is not the attempt's;</li>
 *   <li>{@code -9} an attempt that is already cancelled, expired or failed.</li>
 * </ol>
 *
 * <p><strong>The amount enforced is the one checked here.</strong> The payment link
 * the customer followed is unsigned — anyone can build one for a known
 * {@code service_id} with any {@code amount} and any {@code transaction_param} —
 * so the figure Click reports is attacker-influenced and the only authority is the
 * attempt HorecaOS committed before presenting anything.
 *
 * <p><strong>A business failure is never reported through Complete.</strong> After
 * a successful charge the response may be {@code 0}, {@code -4} or {@code -9} and
 * nothing else. An order that can no longer be fulfilled is therefore credited,
 * answered {@code error: 0}, and then given back through
 * {@code DELETE payment/reversal}. Returning an error instead leaves the customer
 * charged and uncredited while Click retries and finally escalates to its own
 * support.
 */
@Component
public class ClickCallbackProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClickCallbackProcessor.class);

    private final PaymentBindingResolver bindings;
    private final RotationAwareSecrets secrets;
    private final JdbcPaymentAttemptStore attempts;
    private final JdbcPaymentIntentStore intents;
    private final JdbcProviderCallbackStore callbacks;
    private final PaymentAttemptService attemptService;
    private final ClickPaymentAdapter click;
    private final Clock clock;

    public ClickCallbackProcessor(
            PaymentBindingResolver bindings,
            RotationAwareSecrets secrets,
            JdbcPaymentAttemptStore attempts,
            JdbcPaymentIntentStore intents,
            JdbcProviderCallbackStore callbacks,
            PaymentAttemptService attemptService,
            ClickPaymentAdapter click,
            Clock clock) {
        this.bindings = bindings;
        this.secrets = secrets;
        this.attempts = attempts;
        this.intents = intents;
        this.callbacks = callbacks;
        this.attemptService = attemptService;
        this.click = click;
        this.clock = clock;
    }

    /**
     * Decides one arrival and records it.
     *
     * @param bindingSegment the path segment that names the merchant account. Not a
     *                       credential and guessable by design: because Click's
     *                       {@code secret_key} is per service, one shared callback
     *                       URL could not authenticate anything, so the segment says
     *                       which secret to check the signature against and the
     *                       signature does the authenticating
     * @param expectedAction the action the endpoint that was called stands for, so
     *                       that a Complete posted to the Prepare URL is answered
     *                       {@code -3} rather than silently treated as a Prepare
     * @param form           the form exactly as received. Every value stays a string
     *                       until after the signature has been verified, because the
     *                       digest is over the strings Click sent
     */
    public ClickCallbackDecision handle(String bindingSegment, String expectedAction, Map<String, String> form) {

        ClickCallbackRequest request = ClickCallbackRequest.fromForm(form);
        Optional<ProviderBinding> binding = bindings.byCallbackSegment(bindingSegment)
                .filter(candidate -> candidate.providerType() == PaymentProviderType.CLICK);

        if (binding.isEmpty()) {
            // No merchant account is deployed at this path. Nothing is recorded
            // because there is no tenant to record it under — the binding is what
            // supplies one — and the answer is the one that says "no such account
            // here" without saying anything about what does exist.
            log.warn("A Click callback arrived on an unknown binding segment; answering -5.");
            return ClickCallbackDecision.failed(ClickShopApiError.USER_DOES_NOT_EXIST);
        }

        ProviderBinding account = binding.get();
        // Verified before the decision, so that nothing in the decision can reach a
        // database first — but answered in the documented order, which puts -8 ahead
        // of -1. A truncated body fails both checks and is reported as the malformed
        // request it is, while still being recorded as a signature that did not
        // verify, because that is what happened.
        boolean signatureValid = verifySignature(account, request);
        ClickCallbackDecision decision = decide(account, expectedAction, request, signatureValid);

        record(account, request, expectedAction, decision, signatureValid, form);
        return decision;
    }

    private ClickCallbackDecision decide(
            ProviderBinding account, String expectedAction, ClickCallbackRequest request, boolean signatureValid) {

        // -8 first, and for a partially missing body rather than only for an empty
        // one. The Django reference's isset helper is true only when every required
        // field is absent, so a request missing just sign_time sails past its -8 and
        // fails later as something else; the PHP reference is correct and this
        // follows it.
        if (!request.hasEveryRequiredField()) {
            return ClickCallbackDecision.failed(ClickShopApiError.BAD_REQUEST);
        }

        // -1, before any database is touched. The MD5 over the raw received strings
        // is the whole of the authentication on this endpoint.
        if (!signatureValid) {
            return ClickCallbackDecision.failed(ClickShopApiError.SIGN_CHECK_FAILED);
        }

        // -3. The action must be one Click documents, and it must be the one this
        // endpoint stands for: Prepare and Complete sign different field lists, so
        // an action that disagrees with its URL is a request nothing here can answer
        // coherently.
        if (!request.isKnownAction() || !expectedAction.equals(request.action())) {
            return ClickCallbackDecision.failed(ClickShopApiError.ACTION_NOT_FOUND);
        }

        // The service the request names must be this binding's. In practice a
        // mismatch has usually already failed the signature — service_id is inside
        // the digest and the secret is per service — but a binding whose secret
        // leaked would otherwise accept traffic for a service it does not hold.
        // Click documents no code for this; -8 is HorecaOS's choice, on the reading
        // that a request naming somebody else's service is malformed for this
        // endpoint rather than a missing order.
        if (!account.merchantAccountReference().equals(request.serviceId())) {
            log.warn("A Click callback named a service this binding does not hold; answering -8.");
            return ClickCallbackDecision.failed(ClickShopApiError.BAD_REQUEST);
        }

        // -5. The join key is HorecaOS's own merchant_trans_id, which is why it is
        // minted and committed before anything is presented to a customer.
        //
        // The binding is part of the key, not only the tenant. V0027 gives one
        // tenant a separate Click service, secret and callback segment per legal
        // entity, so a tenant predicate alone lets the holder of entity A's secret
        // — A's own restaurant staff, by construction — POST a correctly signed
        // Prepare to A's callback segment carrying entity B's merchant_trans_id.
        // The service check above compares against A's binding and passes, the
        // signature verifies under A's key, and B's order is captured and
        // fiscalized under B's legal entity on A's money. The Payme side already
        // scopes every read by merchant_binding_id; this is the same rule.
        Optional<PaymentAttempt> found = attempts.findByMerchantTransId(
                        account.tenantId(), PaymentProviderType.CLICK, request.merchantTransId())
                .filter(candidate -> candidate.merchantBindingId().equals(account.bindingId()));
        if (found.isEmpty()) {
            return ClickCallbackDecision.failed(ClickShopApiError.USER_DOES_NOT_EXIST);
        }
        PaymentAttempt attempt = found.get();
        int merchantTransactionId = ClickPrepareId.forAttempt(attempt.id());

        // -6, Complete only. The value is a deterministic function of the attempt,
        // so this compares what Click echoed against what this attempt would have
        // been told — no lookup table, and a repeated Prepare cannot produce a
        // second id that makes Complete unresolvable.
        if (request.isComplete() && !ClickPrepareId.matches(attempt.id(), request.merchantPrepareId())) {
            return ClickCallbackDecision.failed(ClickShopApiError.TRANSACTION_DOES_NOT_EXIST);
        }

        // -4, deliberately before -2. A replayed Complete for an order already
        // credited must report "settled", which is what Click understands -4 to
        // mean, rather than trip an amount check against a total that may since have
        // been adjusted. A reversed attempt answers -4 as well: Click's record is
        // that this payment was made, and answering -9 would tell it the payment
        // never happened.
        if (attempt.status() == PaymentAttemptStatus.CAPTURED || attempt.status() == PaymentAttemptStatus.REVERSED) {
            return ClickCallbackDecision.answered(ClickShopApiError.ALREADY_PAID, attempt.id(), merchantTransactionId);
        }

        // Click reporting its own failure. The documented answer is to void the
        // payment locally and reply -9, whichever action this was, and it is checked
        // before the amount because a payment that failed on Click's side is
        // cancelled whatever figure it carries.
        if (request.reportsClickSideFailure()) {
            voidPayment(attempt, request);
            return ClickCallbackDecision.answered(
                    ClickShopApiError.TRANSACTION_CANCELLED, attempt.id(), merchantTransactionId);
        }

        // -2. Compared as whole-som integers rather than with the references' 0.01
        // float tolerance: Django's version of that comparison has a misplaced
        // parenthesis and lets underpayment through, and integers have neither
        // problem. An amount that is not whole som at all is a disagreement about
        // what is being charged, so it is refused rather than rounded into
        // agreement.
        Optional<Long> som = request.amountAsSom();
        if (som.isEmpty() || !new SomAmount(som.get(), attempt.amount().currency()).matches(attempt.amount())) {
            log.warn(
                    "A Click callback for attempt {} carried an amount the attempt does not " + "hold; answering -2.",
                    attempt.id());
            return ClickCallbackDecision.failed(ClickShopApiError.INCORRECT_AMOUNT);
        }

        // -9. Cancelled, expired or failed already: a second Complete against it
        // must not resurrect it.
        if (attempt.status().terminal()) {
            return ClickCallbackDecision.answered(
                    ClickShopApiError.TRANSACTION_CANCELLED, attempt.id(), merchantTransactionId);
        }

        return request.isComplete()
                ? complete(account, attempt, request, merchantTransactionId)
                : prepare(attempt, request, merchantTransactionId);
    }

    /**
     * Prepare: reserve, and answer with an id that is a function of the order.
     *
     * <p>A repeated Prepare returns the same {@code merchant_prepare_id} and writes
     * nothing. That is not an optimisation: Complete carries exactly one
     * {@code merchant_prepare_id}, so an id minted per Prepare call would leave a
     * later Complete pointing at a reservation nobody can identify. Click documents
     * nothing about repeated Prepare — <strong>whether it guarantees at-most-once
     * delivery is an open question with CLICK</strong> — and both reference
     * implementations are idempotent by construction, which is the best evidence
     * available.
     */
    private ClickCallbackDecision prepare(PaymentAttempt attempt, ClickCallbackRequest request, int merchantPrepareId) {

        if (attempt.status() != PaymentAttemptStatus.RESERVED) {
            reserve(attempt, request);
        }
        return ClickCallbackDecision.answered(ClickShopApiError.SUCCESS, attempt.id(), merchantPrepareId);
    }

    /**
     * Complete: credit the order, and never report a business failure.
     *
     * <p>The credit is guarded by the attempt's own state rather than by "have I
     * seen this {@code click_trans_id}", so a replay falls out as {@code -4} above
     * rather than as a second capture.
     *
     * <p>If the order can no longer be fulfilled — its intent was cancelled, expired
     * or failed while the customer was paying — the money still arrived and is still
     * recorded, the answer is still {@code error: 0}, and the reversal is what
     * corrects it. The reversal runs before this returns rather than on a queue: a
     * Complete that Click retries in the meantime finds the attempt captured and is
     * answered {@code -4}, which is correct, so the only cost of doing it here is
     * latency on a response Click sets no deadline for.
     */
    private ClickCallbackDecision complete(
            ProviderBinding account, PaymentAttempt attempt, ClickCallbackRequest request, int merchantConfirmId) {

        // Asked before the capture is recorded, not after. Recording a capture moves
        // the intent to PAID, so a cancellation that happened while the customer was
        // paying is invisible from a moment later — and invisible here means the
        // customer keeps a charge for an order nobody will make.
        boolean fulfillable = fulfillable(attempt);

        PaymentAttempt reserved = attempt;
        if (attempt.status() == PaymentAttemptStatus.INITIATED || attempt.status() == PaymentAttemptStatus.PRESENTED) {
            // Complete implies Click saw a successful Prepare. Reaching here means
            // HorecaOS's own Prepare write did not survive, and the reservation is
            // recorded now rather than refusing a payment that has already been
            // taken from a customer's card.
            log.warn(
                    "A Click Complete arrived for attempt {} with no reservation recorded; "
                            + "reserving before crediting.",
                    attempt.id());
            reserve(attempt, request);
            reserved = attempts.find(attempt.tenantId(), attempt.id()).orElse(attempt);
        }

        attemptService.recordProviderEvent(
                reserved,
                PaymentTransactionType.CAPTURE,
                PaymentAttemptStatus.CAPTURED,
                reserved.amount(),
                request.clickTransId(),
                ProviderEvidence.of("completed", clock.instant()),
                // Click's callbacks carry no payment_id, and nothing documents that
                // click_paydoc_id is the id the reversal and fiscalization paths
                // want. It is recorded as the document it is called, and the
                // payment id is resolved through status_by_mti when one is needed.
                null,
                request.clickPaydocId(),
                clock.instant(),
                null,
                null);

        PaymentAttempt captured =
                attempts.find(reserved.tenantId(), reserved.id()).orElse(reserved);
        if (!fulfillable) {
            reverseAfterAnsweringSuccess(account, captured);
        }

        return ClickCallbackDecision.answered(ClickShopApiError.SUCCESS, captured.id(), merchantConfirmId);
    }

    private void reserve(PaymentAttempt attempt, ClickCallbackRequest request) {
        attemptService.recordProviderEvent(
                attempt,
                PaymentTransactionType.RESERVE,
                PaymentAttemptStatus.RESERVED,
                attempt.amount(),
                request.clickTransId(),
                ProviderEvidence.of("prepared", clock.instant()),
                null,
                request.clickPaydocId(),
                clock.instant(),
                null,
                null);
    }

    /**
     * Voids an attempt Click has reported a failure on.
     *
     * <p>Recorded as a cancellation rather than a failure because Click's own
     * vocabulary for it is {@code -9 Transaction cancelled}, and because the money
     * never moved.
     */
    private void voidPayment(PaymentAttempt attempt, ClickCallbackRequest request) {
        if (attempt.status().terminal()) {
            return;
        }
        attemptService.recordProviderEvent(
                attempt,
                PaymentTransactionType.CANCEL,
                PaymentAttemptStatus.CANCELLED,
                attempt.amount(),
                request.clickTransId(),
                ProviderEvidence.of("click-side-failure", clock.instant()),
                null,
                request.clickPaydocId(),
                clock.instant(),
                null,
                null);
    }

    /**
     * Whether the order this payment belongs to can still be served.
     *
     * <p>Read from the intent, which is what the order's own cancellation moves.
     * An intent that no longer holds its order — cancelled, expired or failed —
     * means the customer has paid for something that will not be made.
     */
    private boolean fulfillable(PaymentAttempt attempt) {
        return intents.find(attempt.tenantId(), attempt.intentId())
                .map(PaymentIntent::status)
                .map(status -> status.holdsTheOrder())
                .orElse(false);
    }

    private void reverseAfterAnsweringSuccess(ProviderBinding account, PaymentAttempt attempt) {
        log.warn(
                "Click credited attempt {} for an order that can no longer be fulfilled; "
                        + "answering error 0 and reversing.",
                attempt.id());

        ProviderOutcome outcome =
                click.reverse(attempt, account, "The order could not be fulfilled after a successful Click charge");

        switch (outcome.classification()) {
            case SUCCESS ->
                attemptService.recordProviderEvent(
                        attempt,
                        PaymentTransactionType.REVERSE,
                        PaymentAttemptStatus.REVERSED,
                        attempt.amount(),
                        outcome.externalPaymentId(),
                        outcome.evidence(),
                        outcome.externalPaymentId(),
                        null,
                        clock.instant(),
                        null,
                        null);
            // The money may or may not have gone back, and the reversal must never
            // be sent again to find out. The attempt carries the question and the
            // resolver settles it.
            case UNCERTAIN, RETRYABLE -> attemptService.markUncertain(attempt, outcome.failureCode());
            // Click refused. Which refusal it was cannot be said — the error_code
            // enumeration is unpublished and is an open question with CLICK — so the
            // attempt stays captured, the customer stays charged, and an operator
            // owns the refund. That is the honest position: the alternative is a
            // state that claims the money went back when it did not.
            case REJECTED ->
                log.error(
                        "Click refused to reverse attempt {} for an order that "
                                + "cannot be fulfilled; the customer is charged and a human must refund: {}",
                        attempt.id(),
                        outcome.detail());
        }
    }

    /**
     * Verifies {@code sign_string} over the raw received strings.
     *
     * <p>Nothing between the wire and the digest reformats a field. Click may send
     * {@code 1000}, {@code 1000.0} or {@code 1000.00} for one amount and each hashes
     * differently, so parsing the amount and rendering it back — even to the same
     * number of decimal places — produces a spurious {@code -1 SIGN CHECK FAILED!}.
     * That is the commonest defect in this integration and a test exists for it.
     *
     * <p>A failure is retried against a freshly resolved secret, per ADR 0028, so
     * that a key rotated between the cache being filled and this request arriving
     * presents as one slow request rather than as a burst of signature failures on
     * a perfectly good binding.
     *
     * <p>That retry goes through {@link RotationAwareSecrets} rather than straight
     * to {@code resolveFresh}, and the difference matters on this endpoint
     * specifically. There is no auth header here: anybody who can reach the URL
     * can send a wrong signature, so a fresh read on every failure would let a
     * stranger drive one secrets-manager round trip per request and point the
     * public callback at the platform's own secret store. The cooldown keeps the
     * rotation recovery and makes the amplification a constant.
     */
    private boolean verifySignature(ProviderBinding binding, ClickCallbackRequest request) {
        String cached = secrets.cached(binding.secretReference()).reveal();
        if (ClickSignature.matches(ClickSignature.expected(cached, request), request.signString())) {
            return true;
        }

        Optional<SecretValue> fresh = secrets.fresh(binding.secretReference());
        if (fresh.isPresent()
                && ClickSignature.matches(
                        ClickSignature.expected(fresh.get().reveal(), request), request.signString())) {
            return true;
        }

        // The binding's toString omits the account reference, so this line names a
        // row and never a restaurant's Click service. A burst of these is the only
        // warning available on an endpoint with no auth header, which is why every
        // one of them is also written to the callback inbox.
        log.warn("A Click callback failed its signature on {}.", binding);
        return false;
    }

    /**
     * Writes the arrival to the ADR 0005 inbox, including the ones that failed.
     *
     * <p>The raw body is <strong>not</strong> stored. It carries {@code sign_string},
     * which is a keyed digest of the merchant's secret key, and ADR 0028 keeps every
     * credential and everything derived from one out of a column. What is stored is
     * the hash of the body, the typed identifiers, and the answer HorecaOS gave — which
     * is what a support conversation with Click is actually argued from. The
     * protected-payload references the schema reserves stay null until ADR 0029's
     * store for whole request bodies exists.
     */
    private void record(
            ProviderBinding binding,
            ClickCallbackRequest request,
            String expectedAction,
            ClickCallbackDecision decision,
            boolean signatureValid,
            Map<String, String> form) {

        CallbackKind kind = ClickCallbackRequest.ACTION_COMPLETE.equals(expectedAction)
                ? CallbackKind.CLICK_COMPLETE
                : CallbackKind.CLICK_PREPARE;
        String reference =
                request.clickTransId() == null || request.clickTransId().isBlank() ? "unknown" : request.clickTransId();

        Instant now = clock.instant();
        try {
            callbacks.record(
                    UUID.randomUUID(),
                    binding.tenantId(),
                    PaymentProviderType.CLICK,
                    binding.bindingId(),
                    kind,
                    reference,
                    bodyHash(form),
                    signatureValid,
                    decision.attemptId(),
                    decision.responseCode(),
                    now,
                    null,
                    null);
        } catch (RuntimeException notRecorded) {
            // The inbox is evidence, not the decision. A callback that cannot be
            // recorded must still be answered, because the alternative is that Click
            // retries a payment HorecaOS has already credited.
            log.error("A Click callback on {} could not be recorded in the inbox.", binding, notRecorded);
        }
    }

    /**
     * A stable digest of the arrival, for the inbox's delivery key.
     *
     * <p>Over the sorted field names and their values, so that two deliveries of one
     * body hash the same however the server chose to order the parameters, and two
     * deliveries with the same {@code click_trans_id} and different content hash
     * differently — which is the point of the key: on Click, Prepare and Complete
     * legitimately arrive under one {@code click_trans_id}.
     */
    private static String bodyHash(Map<String, String> form) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> field : new TreeMap<>(form).entrySet()) {
            canonical
                    .append(field.getKey())
                    .append('=')
                    .append(field.getValue() == null ? "" : field.getValue())
                    .append('&');
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
