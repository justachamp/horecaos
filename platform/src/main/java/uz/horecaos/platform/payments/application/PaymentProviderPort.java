package uz.horecaos.platform.payments.application;

import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PresentationFailure;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderInvoice;
import uz.horecaos.platform.payments.domain.ProviderOutcome;

/**
 * The outbound provider operations payments needs and does not implement
 * (ADR 0013, ADR 0007).
 *
 * <p>Declared here and implemented in {@code payments.infrastructure} by one
 * adapter per provider, the arrangement {@code OrderingTenantContext} sets: the
 * module states what it needs, and whoever can answer does. The two adapters are
 * independent by design — Click's {@code Price} means the opposite of Payme's
 * {@code price}, their error vocabularies share nothing, and their reversal and
 * reconciliation directions are inverted — so nothing is shared between them but
 * this interface.
 *
 * <p><strong>Three rules bind every implementation.</strong>
 *
 * <p><em>Units cross the boundary exactly once.</em> Everything on
 * {@link PaymentAttempt} is whole som. Click's SHOP API and payment calls are som;
 * Click's fiscalization and every Payme amount are tiyin. The adapter converts
 * with {@code TiyinAmount.of(SomAmount)} and by no other means, and converts back
 * with {@code TiyinAmount.toSom()}. A bare numeric amount does not appear in any
 * signature here for exactly this reason.
 *
 * <p><em>A lost response is uncertain, never retryable.</em> A timeout, a 500, a
 * 502 or a transport failure on a mutating call answers
 * {@link ProviderOutcome#uncertain}. Neither provider offers an idempotency key
 * on a call that moves money, so a retry is a second charge. A 4xx is terminal:
 * it is a configuration or programming error and will fail identically.
 *
 * <p><em>The domain never sees a provider code.</em> Click answers HTTP 200 with
 * small negative integers and note strings that must be echoed verbatim; Payme
 * answers HTTP 200 with a JSON-RPC error whose message is a localised
 * {@code {ru, uz, en}} object. There is no shared error type, so the whole mapping
 * belongs to the adapter and only a HorecaOS failure code comes back.
 */
public interface PaymentProviderPort {

    /**
     * Presents the payment to the customer.
     *
     * <p>Produces a link, a QR payload, or a pushed invoice, and returns no
     * payment. Every checkout surface on both providers is unauthenticated — an
     * arbitrary amount may be put in a Click payment link or a Payme base64 link
     * by anyone — so nothing this returns may ever credit an order. The
     * authoritative signal arrives inbound, on both providers, which is the one
     * thing this port genuinely abstracts.
     *
     * <p>Optional per provider: an installation declares whether it supports
     * presentation through its ADR 0026 capability snapshot.
     *
     * <p>{@code request} names what the caller would like and never what the
     * adapter must produce. Payme has no push at all, a Click binding with no
     * {@code merchant_id} cannot build a link, and the {@link ProviderInvoice} that
     * comes back says which surface actually exists. Nothing on the request is
     * persisted: its recipient is a phone number and therefore personal data under
     * ADR 0029, carried for the length of one call and no longer.
     *
     * @throws PresentationFailure.Refused   the provider or the configuration said
     *                                       no, before anything could have
     *                                       happened. The attempt is untouched
     * @throws PresentationFailure.Uncertain a <em>mutating</em> presentation was
     *                                       sent and its answer was lost. The call
     *                                       must never be repeated; the caller
     *                                       marks the attempt uncertain and the
     *                                       resolver settles it
     */
    ProviderInvoice createInvoice(PaymentAttempt attempt, ProviderBinding binding,
            PresentationRequest request);

    /**
     * Discovers what actually happened to an attempt whose outcome is unknown.
     *
     * <p>This is the resolver named on the attempt's uncertainty, and it is the
     * only thing that may follow an uncertain outcome. On Click it is
     * {@code payment/status_by_mti} keyed on the attempt's own
     * {@code merchantTransId} and {@code businessDate}, then {@code payment/status}
     * for whatever payment that names. On Payme it is {@code CheckTransaction},
     * which must not expire a transaction as a side effect.
     *
     * <p>Answering {@link ProviderOutcome.Classification#UNCERTAIN} again is a
     * legitimate result and means "still in flight" — Click's
     * {@code payment_status} of {@code 0} or {@code 1} is created and in
     * processing, neither of which is money, and several of Click's own examples
     * pair {@code payment_status: 1} with {@code error_note: "Success"}. A "not
     * found" from Click must <em>not</em> be reported as a failure that unblocks a
     * retry: the business date is undocumented, and on this provider absence of
     * evidence is not evidence of absence.
     *
     * <p>Never mutates.
     */
    ProviderOutcome queryOutcome(PaymentAttempt attempt, ProviderBinding binding);

    /**
     * Gives a captured payment back, where the provider allows it.
     *
     * <p>Supported on Click only, and even there the reversal takes no amount —
     * there is no partial reversal in the documented API — requires an online-card
     * payment, is bounded by the reporting month, and may still be refused by the
     * card scheme. On Payme this must answer
     * {@link ProviderOutcome.Classification#REJECTED} with a failure code saying
     * so, because a Payme refund is initiated in the cabinet and arrives inbound as
     * {@code CancelTransaction}, which HorecaOS can only veto. Ask
     * {@link ProviderBinding#supportsReversal()} before calling; a capability the
     * console can render beats an exception the operator discovers.
     *
     * <p>This is also the second half of the rule that a business failure is never
     * reported through Click's Complete: after a successful charge, Complete may
     * answer only {@code -4} or {@code -9}, so an unfulfillable order is answered
     * {@code error: 0} and reversed through here. Returning an error instead leaves
     * the customer charged and uncredited while CLICK retries.
     */
    ProviderOutcome reverse(PaymentAttempt attempt, ProviderBinding binding, String reason);

    /** Which provider this adapter speaks for, so the router can pick one. */
    uz.horecaos.platform.payments.domain.PaymentProviderType providerType();
}
