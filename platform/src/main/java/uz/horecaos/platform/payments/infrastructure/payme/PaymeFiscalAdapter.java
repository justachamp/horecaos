package uz.horecaos.platform.payments.infrastructure.payme;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.payments.application.FiscalReceiptPort;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalSubmission;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.TiyinAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * Payme as {@link FiscalReceiptPort} (ADR 0013, ADR 0038).
 *
 * <p>There is no call to make, and that is the whole shape of it. Payme's fiscal
 * data travels <em>outbound with the checkout</em>, in the {@code detail} object
 * fixed before the customer pays, and the outcome comes back inbound and
 * asynchronously through {@code SetFiscalData}. Click is the mirror image: it
 * fiscalizes strictly after capture, because {@code submit_items} needs a CLICK
 * {@code payment_id} that does not exist earlier. So where the Click adapter makes
 * an HTTP request and reads back, this one has nothing to send and nobody to ask.
 *
 * <p>What it does instead is the only useful thing available at this point:
 * <strong>it proves the receipt can lawfully and arithmetically be expressed in
 * Payme's {@code detail} object, and refuses the document now if it cannot.</strong>
 * The alternative is discovering it at a tax inspection. Two conditions are
 * refusals rather than warnings, and both are permanent facts about the provider
 * rather than transient failures:
 *
 * <ul>
 * <li>A <strong>marked good</strong>. Click has {@code Labels}; Payme has no field
 * for a marking code anywhere in {@code detail}. A tenant selling marked goods must
 * have Payme removed from the channel's payment methods, and this is where that is
 * discovered.</li>
 * <li>A receipt whose lines <strong>do not sum to the charge</strong>. The docs
 * never state the arithmetic — it is open question U11 — so it is enforced here,
 * against the amount the intent committed before any link was built.</li>
 * </ul>
 *
 * <p>A document that passes is answered {@link FiscalSubmission#accepted}, which
 * leaves it {@code SUBMITTED} with {@code AWAITING_PROVIDER}. That is the honest
 * state: the lines are correct and Payme has them, and whether the tax authority
 * accepted them is unknown until {@code SetFiscalData} arrives — which it may never
 * do, because that method is optional for Payme to call and there is no
 * merchant-initiated retry. {@code SetFiscalData} arriving is also not proof of a
 * receipt: a non-zero {@code status_code} reports an ОФД failure. ADR 0038's
 * sweeper owns the document that stays here.
 *
 * <p><strong>What is not yet wired, stated rather than hidden.</strong> The
 * {@code detail} object built below is validated and then discarded, because the
 * two surfaces that can carry it are both out of this method's reach.
 * {@code ProviderInvoice} has no channel for POST form fields, so the checkout
 * form's {@code detail} field cannot be populated from
 * {@link PaymeProviderAdapter#createInvoice}; and the other surface —
 * {@code CheckPerformTransaction}'s {@code detail} result member — is inbound and
 * belongs to {@link PaymeMerchantApi}, which deliberately does not return it today
 * so that two places cannot disagree about what is on the receipt. The docs mark
 * {@code items} required on the one page and optional on the other (U10), so which
 * surface Payme actually reads is a question only a sandbox can settle. When it is
 * settled, the builder that feeds it is {@link PaymeReceiptDetail} and it is already
 * validated by this method.
 */
@Component
public class PaymeFiscalAdapter implements FiscalReceiptPort {

    private static final Logger log = LoggerFactory.getLogger(PaymeFiscalAdapter.class);

    private final JdbcPaymentIntentStore intents;
    private final Clock clock;

    public PaymeFiscalAdapter(JdbcPaymentIntentStore intents, Clock clock) {
        this.intents = intents;
        this.clock = clock;
    }

    @Override
    public PaymentProviderType providerType() {
        return PaymentProviderType.PAYME;
    }

    @Override
    public FiscalSubmission submit(FiscalDocument document, ProviderBinding binding) {
        Instant now = clock.instant();

        Optional<PaymentIntent> intent = document.paymentIntentId() == null
                ? Optional.empty()
                : intents.find(document.tenantId(), document.paymentIntentId());
        if (intent.isEmpty()) {
            // Without the committed amount there is nothing to check the lines
            // against, and a receipt checked against itself is not checked. Reported
            // uncertain rather than rejected: nothing has been refused, the check
            // simply could not be run.
            return FiscalSubmission.uncertain(
                    "The payment intent behind this document could not be read, so the receipt "
                            + "lines cannot be checked against the amount charged", now);
        }

        try {
            PaymeReceiptDetail.of(document.lines(), TiyinAmount.of(intent.get().amount()));
        } catch (PaymeReceiptDetail.PaymeReceiptRefused refused) {
            // A HorecaOS code and not a Payme one, because none of these conditions has
            // a Payme code: Payme would have accepted the wrong receipt.
            log.warn("Fiscal document {} cannot be expressed in Payme's detail object: {}",
                    document.id(), refused.code());
            return FiscalSubmission.rejected(refused.code(), refused.getMessage(), now);
        }

        return FiscalSubmission.accepted(now);
    }
}
