package uz.horecaos.platform.payments.infrastructure.click;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.payments.application.FiscalReceiptPort;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.FiscalSubmission;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.domain.TiyinAmount;
import uz.horecaos.platform.payments.infrastructure.click.ClickMerchantApi.ClickResponse;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;

/**
 * Click as {@link FiscalReceiptPort} (ADR 0013, ADR 0038).
 *
 * <p>Click fiscalizes strictly <em>after</em> capture, because
 * {@code payment/ofd_data/submit_items} takes a CLICK {@code payment_id} that does
 * not exist before the payment does. That single fact orders everything here, and
 * it is the opposite of Payme, whose fiscal data is fixed before the customer pays.
 *
 * <p><strong>{@code Price} is the line total and every amount is tiyin.</strong>
 * Both halves of that sentence are traps. Payme's {@code price} is the
 * <em>unit</em> price, so a shared line builder between the two adapters would
 * fiscalize an order at quantity squared times its value — which is why
 * {@link FiscalReceiptLine} carries unit price and quantity and each adapter
 * derives what its own provider means. And Click's payment call for this very
 * payment was in som while these fields are in tiyin, which is why every amount
 * below goes through {@link TiyinAmount#of} and none is a bare number.
 *
 * <p><strong>A lost {@code submit_items} is never resubmitted.</strong> Whether a
 * second submission for one {@code payment_id} rejects, replaces or duplicates the
 * receipt is not documented and is an open question with CLICK. So the resolution
 * is a read-back through {@code GET payment/ofd_data/…}: a populated
 * {@code qrCodeURL} means the first submission worked, whatever its response would
 * have said. A duplicate document with a tax authority is not a mistake that can be
 * withdrawn afterwards.
 */
@Component
public class ClickFiscalAdapter implements FiscalReceiptPort {

    private static final Logger log = LoggerFactory.getLogger(ClickFiscalAdapter.class);

    /** Click types {@code Name} as {@code string(63)}. */
    private static final int NAME_LIMIT = 63;

    private final ClickMerchantApi click;
    private final JdbcPaymentAttemptStore attempts;
    private final Clock clock;

    public ClickFiscalAdapter(ClickMerchantApi click, JdbcPaymentAttemptStore attempts, Clock clock) {
        this.click = click;
        this.attempts = attempts;
        this.clock = clock;
    }

    @Override
    public PaymentProviderType providerType() {
        return PaymentProviderType.CLICK;
    }

    @Override
    public FiscalSubmission submit(FiscalDocument document, ProviderBinding binding) {
        Instant now = clock.instant();

        if (document.lines().isEmpty()) {
            // Click requires at least one line, and a receipt with none would be
            // evidence of nothing. Refused here rather than by Click, so the reason
            // recorded against the document is the real one.
            return FiscalSubmission.rejected("NO_LINES", "A fiscal receipt needs at least one line", now);
        }

        Optional<String> paymentId = capturedPaymentId(document, binding);
        if (paymentId.isEmpty()) {
            // Nothing has been sent and nothing can be, because submit_items has no
            // argument to be called with until Click has settled the payment.
            // Reported uncertain rather than failed: a failure would be read as
            // "the provider refused", and the provider has not been asked.
            log.info("Fiscal document {} has no CLICK payment_id yet; submission deferred", document.id());
            return FiscalSubmission.uncertain("No CLICK payment_id yet; submit_items cannot precede capture", now);
        }

        // A resubmission reads back first. On a first submission this would be a
        // guaranteed-empty GET, so it is skipped; on any later one it is the only
        // thing standing between a lost response and a second receipt.
        if (document.status() != FiscalStatus.PENDING) {
            Optional<FiscalSubmission> alreadyIssued = readBack(binding, paymentId.get(), now);
            if (alreadyIssued.isPresent()) {
                log.info("Click already holds a receipt for payment {}; not resubmitting", paymentId.get());
                return alreadyIssued.get();
            }
        }

        List<Map<String, Object>> items = new ArrayList<>(document.lines().size());
        SomAmount total = null;
        for (FiscalReceiptLine line : document.lines()) {
            if (line.commissionTin() == null && line.commissionPinfl() == null) {
                // Click requires CommissionInfo and requires it to carry one of the
                // two. Whose taxpayer id belongs there — the restaurant as the
                // principal, or HorecaOS as its agent — is an open question with CLICK
                // and with legal, recorded in ADR 0013's open inputs. Refusing is
                // the only honest answer while it is open: a receipt filed against
                // the wrong taxpayer is worse than a receipt not filed.
                return FiscalSubmission.rejected(
                        "NO_COMMISSION_PARTY", "A Click receipt line needs a TIN or a PINFL in CommissionInfo", now);
            }
            items.add(item(line));
            total = total == null ? line.lineTotal() : total.plus(line.lineTotal());
        }

        // The whole payment was settled by card, because a Click payment is by
        // definition not cash and HorecaOS does not split tender inside one Click
        // payment. Whether Click requires received_* to sum to the Price total is
        // not stated anywhere and is an open question with CLICK; sending the sum
        // is the reading its own worked example supports.
        TiyinAmount zero = new TiyinAmount(0, total.currency());
        ClickResponse submitted = click.submitItems(binding, paymentId.get(), items, TiyinAmount.of(total), zero, zero);

        if (submitted.uncertain()) {
            Optional<FiscalSubmission> issued = readBack(binding, paymentId.get(), now);
            return issued.orElseGet(() ->
                    FiscalSubmission.uncertain("submit_items did not answer; read back before any resubmission", now));
        }
        if (!submitted.successful()) {
            // A non-zero status is the evidence that there is no receipt. Which
            // non-zero one cannot be said: the error_code enumeration is
            // unpublished, so the code travels verbatim for a human.
            return FiscalSubmission.rejected(
                    String.valueOf(submitted.body().get("error_code")), submitted.describe(), now);
        }

        // Accepted. The receipt itself comes from the OFD, and Click does not say
        // how long that round trip takes, so an empty read here means "not yet"
        // rather than "never" and the document waits in SUBMITTED.
        return readBack(binding, paymentId.get(), now).orElseGet(() -> FiscalSubmission.accepted(now));
    }

    /**
     * {@code GET payment/ofd_data/{service_id}/{payment_id}}.
     *
     * <p>Empty when Click has no receipt yet. Public because it is also how a sweep
     * settles a document left in {@code SUBMITTED} — the state that is reachable
     * indefinitely on both providers and that somebody watches.
     */
    public Optional<FiscalSubmission> readBack(ProviderBinding binding, String paymentId, Instant at) {
        ClickResponse evidence = click.ofdData(binding, paymentId);
        if (!evidence.successful()) {
            return Optional.empty();
        }
        // camelCase, unlike every other field in this API.
        String qrCodeUrl = evidence.field("qrCodeURL");
        if (!ClickReceiptUrl.issued(qrCodeUrl)) {
            return Optional.empty();
        }
        return Optional.of(FiscalSubmission.issued(ClickReceiptUrl.parse(qrCodeUrl, paymentId, at), at));
    }

    /**
     * One {@code Item}, in Click's PascalCase and Click's tiyin.
     *
     * <p>{@code Price} is the line total and {@code GoodPrice} the unit price, which
     * is the reverse of the intuition Payme's field names create.
     */
    private static Map<String, Object> item(FiscalReceiptLine line) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Name", trim(line.fiscalName()));
        if (line.barcode() != null) {
            item.put("Barcode", line.barcode());
        }
        if (line.marked()) {
            item.put("Labels", line.markingCodes());
        }
        // The tax authority's product classifier, and the package code that pairs
        // with it. Both mandatory; ADR 0038 is where they come from.
        item.put("SPIC", line.mxikCode());
        if (line.unitCode() != null) {
            item.put("Units", line.unitCode());
        }
        item.put("PackageCode", line.packageCode());
        item.put("GoodPrice", TiyinAmount.of(line.unitPrice()).value());
        item.put("Price", TiyinAmount.of(line.lineTotal()).value());
        // Click types Amount as uint64, so a fractional quantity is unrepresentable
        // and whether the field is scaled — ×1000 is common in Uzbek OFD schemas —
        // is an open question with CLICK. FiscalReceiptLine already refuses a
        // non-positive integer quantity, so nothing is lost here today.
        item.put("Amount", line.quantity());
        item.put("VAT", TiyinAmount.of(line.taxAmount()).value());
        item.put("VATPercent", line.vatPercent());
        if (line.discount() != null) {
            item.put("Discount", TiyinAmount.of(line.discount()).value());
        }

        Map<String, Object> commission = new LinkedHashMap<>();
        if (line.commissionTin() != null) {
            commission.put("TIN", line.commissionTin());
        }
        if (line.commissionPinfl() != null) {
            commission.put("PINFL", line.commissionPinfl());
        }
        item.put("CommissionInfo", commission);
        return item;
    }

    /**
     * Click's {@code payment_id} for the order behind this document.
     *
     * <p>Read from the captured attempt rather than passed in, because
     * {@link FiscalReceiptPort} is deliberately provider-neutral and a
     * {@code payment_id} is not a concept Payme has.
     */
    private Optional<String> capturedPaymentId(FiscalDocument document, ProviderBinding binding) {
        if (document.paymentIntentId() == null) {
            return Optional.empty();
        }
        return attempts.listForIntent(binding.tenantId(), document.paymentIntentId()).stream()
                .filter(attempt -> attempt.status() == PaymentAttemptStatus.CAPTURED)
                .map(PaymentAttempt::externalPaymentId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst();
    }

    private static String trim(String name) {
        return name.length() <= NAME_LIMIT ? name : name.substring(0, NAME_LIMIT);
    }
}
