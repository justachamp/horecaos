package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;

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
 * aside — no change there, exactly as its own javadoc says.
 *
 * <p><strong>What this class is not.</strong> It does not build a Click {@code
 * Items} array or a Payme {@code detail} object — {@link
 * uz.horecaos.platform.payments.infrastructure.click.ClickFiscalAdapter} and {@link
 * uz.horecaos.platform.payments.infrastructure.payme.PaymeFiscalAdapter} already do,
 * correctly, and {@code PartnerFiscalizationPort}'s own javadoc names exactly why a
 * second place must not: building a receipt line needs the payment attempt, the
 * merchant binding, and the som-to-tiyin conversion, none of which belongs outside
 * those adapters. What it also does not do, and what ADR 0038 leaves genuinely
 * unbuilt, is turn an order's priced lines into ИКПУ-classified {@link
 * FiscalReceiptLine}s — {@code catalog.fiscal_classifications} and the bulk
 * assignment tooling over it are the ADR's own "not built" items, and reaching into
 * catalog data this class has no capability to read is not this change's to add.
 * {@link #syntheticLine} is the honest stand-in until that exists: one line for the
 * whole order total, named and coded as visibly synthetic rather than presented as
 * real classification data. A tenant that goes live with Click before that lands
 * needs a real line builder before its receipts are correct, not before its receipts
 * exist at all.
 */
@Service
public class PartnerFiscalizationBridge implements PartnerFiscalizationPort {

    private static final Logger log = LoggerFactory.getLogger(PartnerFiscalizationBridge.class);

    /**
     * A visibly fake ИКПУ code, until ADR 0038's catalog classification reaches this
     * seam. Real ИКВУ codes are seventeen digits; this one is deliberately not a
     * plausible one, so a receipt built from it reads as what it is on sight rather
     * than as a wrong real code nobody would think to question.
     */
    private static final String SYNTHETIC_MXIK_CODE = "00000000000000000";

    private static final String SYNTHETIC_PACKAGE_CODE = "0000000";

    /** Used only when the seller's real TIN cannot be resolved (see {@link #commissionTin}). */
    private static final String SYNTHETIC_TIN = "000000000";

    private final PaymentFiscalService fiscalDocuments;
    private final PaymentBindingResolver bindings;
    private final JdbcPaymentIntentStore intents;
    private final OrderDirectory orders;
    private final LegalEntityDirectory legalEntities;
    private final Clock clock;

    public PartnerFiscalizationBridge(
            PaymentFiscalService fiscalDocuments,
            PaymentBindingResolver bindings,
            JdbcPaymentIntentStore intents,
            OrderDirectory orders,
            LegalEntityDirectory legalEntities,
            Clock clock) {
        this.fiscalDocuments = fiscalDocuments;
        this.bindings = bindings;
        this.intents = intents;
        this.orders = orders;
        this.legalEntities = legalEntities;
        this.clock = clock;
    }

    @Override
    public Outcome retry(UUID tenantId, UUID documentId, String idempotencyKey) {
        Optional<FiscalDocument> found = fiscalDocuments.find(tenantId, documentId);
        if (found.isEmpty()) {
            // The fiscal module claimed a document that this module's own read of
            // the same row (payments.fiscal_documents, a view over fiscal's table —
            // see V0039) cannot find. Not reachable in the ordinary case; reported
            // uncertain rather than asserted against, because nothing was sent.
            log.warn("Fiscal document {} was claimed for submission but payments cannot read it", documentId);
            return Outcome.UNCERTAIN;
        }
        FiscalDocument document = found.get();

        if (document.status() == FiscalStatus.ISSUED) {
            // Already resolved before this call was made — an operator retry
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

        Optional<ProviderBinding> binding = bindings.resolve(tenantId, legalEntityId, providerType, LocalDate.now(clock));
        if (binding.isEmpty() || !binding.get().supportsPartnerFiscalization()) {
            return Outcome.NO_PROVIDER_PATH;
        }

        FiscalDocument submittable = document.lines().isEmpty() ? withSyntheticLines(document) : document;

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

    /**
     * The synthetic line documented on the class, built from what this module can
     * actually read: the intent's committed total, the order's own display number,
     * and — best effort — the seller's real TIN for {@code CommissionInfo}, which
     * Click refuses a line without.
     */
    private FiscalDocument withSyntheticLines(FiscalDocument document) {
        Optional<PaymentIntent> intent = document.paymentIntentId() == null
                ? Optional.empty()
                : intents.find(document.tenantId(), document.paymentIntentId());
        if (intent.isEmpty()) {
            return document;
        }
        SomAmount amount = intent.get().amount();
        String fiscalName = orders.summary(document.tenantId(), document.orderId())
                .map(order -> "Order " + order.publicOrderNumber())
                .orElse("Order " + document.orderId());

        FiscalReceiptLine line = syntheticLine(document, fiscalName, amount);
        return copyWithLines(document, List.of(line));
    }

    private FiscalReceiptLine syntheticLine(FiscalDocument document, String fiscalName, SomAmount amount) {
        return new FiscalReceiptLine(
                fiscalName,
                SYNTHETIC_MXIK_CODE,
                SYNTHETIC_PACKAGE_CODE,
                null,
                1,
                amount,
                new SomAmount(0, amount.currency()),
                0,
                null,
                null,
                List.of(),
                commissionTin(document),
                null);
    }

    /**
     * Best-effort resolution of the seller's real TIN through the order's own
     * location, falling back to a visibly synthetic one. {@link LegalEntityDirectory}
     * answers by location and business date, never by an entity id alone, so this
     * asks the same question the order's own fiscalization already asked and
     * accepts the answer only when it names the same entity the document snapshotted
     * — a location whose assignment changed since must not attribute today's
     * resolution to yesterday's sale.
     */
    private String commissionTin(FiscalDocument document) {
        return orders.summary(document.tenantId(), document.orderId())
                .flatMap(
                        order -> legalEntities.sellerFor(document.tenantId(), order.locationId(), LocalDate.now(clock)))
                .filter(seller -> seller.legalEntityId().equals(document.legalEntityId()))
                .map(FiscalSeller::taxpayerNumber)
                .orElse(SYNTHETIC_TIN);
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
