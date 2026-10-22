package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;

/**
 * The other half of ADR 0038's rollout stage 4: {@code fiscal.api}'s consumer-declared
 * port, implemented where the adapters it needs to reach already live (ADR 0013,
 * ADR 0038).
 *
 * <p>The exact shape {@code fiscal.api.PartnerFiscalizationPort}'s own javadoc asks
 * for. {@code ordering.api.PaymentIntentPort} is implemented by {@code
 * PaymentIntentService} here in {@code payments.application}; {@code
 * fulfillment.api.OrderProgressPort} is implemented by {@code ordering}'s {@code
 * OrderProgressAdapter} the same way. This class is the third: a thin translator
 * between a document id {@code fiscal} hands in and the {@code FiscalReceiptPort}
 * dispatch {@link PaymentFiscalService} already owns. The moment this bean exists,
 * {@code FiscalPortConfiguration}'s {@code @ConditionalOnMissingBean} stand-in steps
 * aside -- no change there, exactly as its own javadoc says.
 *
 * <p><strong>What it does not do</strong> is build a Click {@code Items} array or a Payme {@code detail}
 * object -- {@link uz.horecaos.platform.payments.infrastructure.click.ClickFiscalAdapter} and {@link
 * uz.horecaos.platform.payments.infrastructure.payme.PaymeFiscalAdapter} already do, and {@code
 * PartnerFiscalizationPort}'s own javadoc names exactly why a second place must not: building a receipt
 * line for a provider needs the payment attempt, the merchant binding and the som-to-tiyin conversion,
 * none of which belongs outside those adapters.
 *
 * <p><strong>What it does is give every order its receipt lines</strong> (ADR 0166 Decision 6): {@link
 * ReceiptLineBuilder} turns the order -- its priced lines, its modifier options, its delivery fee, the
 * discounts it carries and the points redeemed against it -- into {@link FiscalReceiptLine}s, and this
 * class sends them. There is no fallback. An order the builder cannot build is answered with the obstacle
 * ({@link Outcome#BLOCKED_CLASSIFICATION}, {@link Outcome#BLOCKED_TAX}, {@link
 * Outcome#BLOCKED_RECONCILIATION}, {@link Outcome#BLOCKED_MARKS}), nothing is sent, and the document is
 * blocked with a reason an operator can act on. The one-line placeholder this class used to file for the
 * order total, named and coded as visibly fake, was refused outside a sandbox and is deleted: a receipt
 * of one line, a placeholder classification, VAT 0 and possibly a zero TIN is filed with the tax
 * authority and can only be corrected, never withdrawn.
 */
@Service
public class PartnerFiscalizationBridge implements PartnerFiscalizationPort {

    private static final Logger log = LoggerFactory.getLogger(PartnerFiscalizationBridge.class);

    private final PaymentFiscalService fiscalDocuments;
    private final PaymentBindingResolver bindings;
    private final ReceiptLineBuilder lines;
    private final PresentedReceipts presented;
    private final Clock clock;

    @Autowired
    public PartnerFiscalizationBridge(
            PaymentFiscalService fiscalDocuments,
            PaymentBindingResolver bindings,
            ReceiptLineBuilder lines,
            PresentedReceipts presented,
            Clock clock) {
        this.fiscalDocuments = fiscalDocuments;
        this.bindings = bindings;
        this.lines = lines;
        this.presented = presented;
        this.clock = clock;
    }

    /** For callers whose providers never carry the lines on the checkout (Click only). */
    public PartnerFiscalizationBridge(
            PaymentFiscalService fiscalDocuments,
            PaymentBindingResolver bindings,
            ReceiptLineBuilder lines,
            Clock clock) {
        this(fiscalDocuments, bindings, lines, PresentedReceipts.NONE, clock);
    }

    @Override
    public Outcome retry(UUID tenantId, UUID documentId, String idempotencyKey) {
        Optional<FiscalDocument> found = fiscalDocuments.find(tenantId, documentId);
        if (found.isEmpty()) {
            // The fiscal module claimed a document that this module's own read of
            // the same row (payments.fiscal_documents, a view over fiscal's table --
            // see V0039) cannot find. Not reachable in the ordinary case; reported
            // uncertain rather than asserted against, because nothing was sent.
            log.warn("Fiscal document {} was claimed for submission but payments cannot read it", documentId);
            return Outcome.UNCERTAIN;
        }
        FiscalDocument document = found.get();

        if (document.status() == FiscalStatus.ISSUED) {
            // Already resolved before this call was made -- an operator retry
            // landing on a document a concurrent submission already closed, or a
            // Payme SetFiscalData that arrived between the claim and this call
            // (JdbcFiscalLifecycleStore.block is itself conditional on SUBMITTED
            // for exactly this race). Nothing is sent either way: a document
            // already holding a receipt is the one case this bridge can call
            // ALREADY_ISSUED with certainty, because it never asked the provider.
            return Outcome.ALREADY_ISSUED;
        }
        UUID legalEntityId = document.legalEntityId();
        PaymentProviderType providerType = document.providerType();
        if (legalEntityId == null || providerType == null) {
            return Outcome.NO_PROVIDER_PATH;
        }

        Optional<ProviderBinding> binding =
                bindings.resolve(tenantId, legalEntityId, providerType, LocalDate.now(clock));
        if (binding.isEmpty() || !binding.get().supportsPartnerFiscalization()) {
            return Outcome.NO_PROVIDER_PATH;
        }

        FiscalDocument submittable = document;
        Optional<List<FiscalReceiptLine>> carried =
                providerType == PaymentProviderType.PAYME && document.paymentIntentId() != null
                        ? presented.linesOfIntent(tenantId, document.paymentIntentId())
                        : Optional.empty();
        if (carried.isPresent()) {
            // ADR 0166 Decision 6, "stored as sent": Payme took these lines WITH THE CHECKOUT, before the
            // customer paid, so they are the receipt. Building them again from the order as it stands now
            // would file a different document from the one Payme holds, or block a payment that has already
            // been taken because the catalogue moved in between.
            submittable = copyWithLines(document, carried.get());
        } else if (document.lines().isEmpty()) {
            Optional<String> filedUnder = fiscalDocuments.sellerTaxpayerNumber(tenantId, documentId);
            if (filedUnder.isEmpty()) {
                // ADR 0166, Decision 3 and 9: the seller of a real receipt is the taxpayer the document
                // was opened under, and Click files what it is sent. A document with no snapshot has no
                // seller to name, and a receipt is never filed under a TIN of zeros: nothing is built and
                // nothing is sent.
                log.warn(
                        "Fiscal document {} for order {} was not sent: it holds no seller snapshot",
                        documentId,
                        document.orderId());
                return Outcome.REJECTED;
            }
            switch (lines.build(document, filedUnder.get())) {
                case ReceiptLineBuilder.Result.MarksIncomplete incomplete -> {
                    return Outcome.BLOCKED_MARKS;
                }
                case ReceiptLineBuilder.Result.Unprojectable unprojectable -> {
                    log.warn(
                            "Fiscal document {} for order {} was not sent: {}",
                            documentId,
                            document.orderId(),
                            unprojectable.reason());
                    return Outcome.REJECTED;
                }
                case ReceiptLineBuilder.Result.Blocked blocked -> {
                    return switch (blocked.obstacle()) {
                        case CLASSIFICATION -> Outcome.BLOCKED_CLASSIFICATION;
                        case TAX -> Outcome.BLOCKED_TAX;
                        case RECONCILIATION -> Outcome.BLOCKED_RECONCILIATION;
                    };
                }
                case ReceiptLineBuilder.Result.Lines built -> submittable = copyWithLines(document, built.lines());
            }
        }

        FiscalStatus result = fiscalDocuments.submit(submittable, binding.get(), clock.instant());
        return switch (result) {
            case ISSUED -> Outcome.ISSUED;
            case FAILED -> Outcome.REJECTED;
            // SUBMITTED (the ordinary "asked, no answer yet" rest state on both
            // providers), and every status this bridge did not just put the
            // document into (PENDING, BLOCKED, NOT_APPLICABLE) if PaymentFiscalService
            // ever left it unchanged: none of these settles anything, so none is
            // resubmitted.
            default -> Outcome.UNCERTAIN;
        };
    }

    private static FiscalDocument copyWithLines(FiscalDocument document, List<FiscalReceiptLine> lines) {
        return new FiscalDocument(
                document.id(),
                document.tenantId(),
                document.orderId(),
                document.legalEntityId(),
                document.paymentIntentId(),
                document.paymentTransactionId(),
                document.providerType(),
                document.documentType(),
                document.correctsDocumentId(),
                document.status(),
                document.reasonCode(),
                document.reasonNote(),
                lines,
                document.evidence(),
                document.version(),
                document.createdAt());
    }
}
