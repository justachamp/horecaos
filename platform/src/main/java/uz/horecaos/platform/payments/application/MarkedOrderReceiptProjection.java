package uz.horecaos.platform.payments.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup.FiscalClassificationFacts;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderMarksPort.MarkRef;
import uz.horecaos.platform.ordering.api.OrderMarksPort.ReceiptLine;
import uz.horecaos.platform.ordering.api.OrderMarksPort.ReceiptSource;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.SomAmount;

/**
 * The receipt of a marked order, built from its order lines and the codes scanned at the pass (ADR 0161,
 * Decision 6).
 *
 * <p>A marked order <em>never</em> takes {@link PartnerFiscalizationBridge}'s synthetic aggregate line: a
 * receipt that carries one visibly fake line for the whole total carries no code, and the codes are the
 * point. This projection sits in {@code payments}, beside the bridge, and not in {@code fiscal}:
 * {@link FiscalReceiptLine} and {@link FiscalDocument} are {@code payments} types and {@code fiscal}
 * imports nothing from {@code payments}, so constructing them from {@code fiscal} would add the
 * fiscal-to-payments edge that closes the cycle the table's placement removes.
 *
 * <p>What it builds: one {@link FiscalReceiptLine} per live order line. A marked line becomes one line of
 * {@code quantity = N} whose {@code markingCodes} are the N scanned codes in sequence order; whether Click
 * accepts N labels on one item or wants N items of quantity one is an open input to be proven in its
 * sandbox, and if it refuses, the split lives here and nothing else changes. The other lines of a marked
 * order are built from the same classification, because a receipt that covered only its marked lines would
 * not add up to the sale.
 *
 * <p><strong>The codes are decrypted here, once per document, and nowhere else.</strong> The ciphertext
 * comes through {@link OrderMarksPort}, and it is revealed under the purpose {@link #PURPOSE} only when
 * every marked line is complete and the payment path is one that can carry them, so a code is never
 * decrypted for a document that cannot be sent. One ADR 0027 fact records the disclosure -- the document,
 * the order and how many codes, never a code.
 *
 * <p><strong>What it does not decide.</strong> Tax: each line's VAT share is the quote's recorded share and
 * is never recomputed, and the whole-percent rate both providers need is derived from that share and the
 * line's net amount (ADR 0166's tax-profile-based line builder replaces the derivation for every order). A
 * line the catalog has not classified is given the same visibly synthetic placeholders the aggregate line
 * uses, and a <em>marked</em> line without a classification blocks instead, because a coded unit under a
 * placeholder ИКПУ is worse than no receipt.
 */
@Service
public class MarkedOrderReceiptProjection {

    private static final Logger log = LoggerFactory.getLogger(MarkedOrderReceiptProjection.class);

    /** The purpose the codes are decrypted under; ADR 0029 records it beside the reveal. */
    public static final String PURPOSE = "fiscal-receipt";

    /** Visibly not an ИКПУ, as in {@link PartnerFiscalizationBridge}: real ones are seventeen digits. */
    private static final String PLACEHOLDER_MXIK = "00000000000000000";

    private static final String PLACEHOLDER_PACKAGE = "0000000";

    /** Click caps {@code Name} at 63 characters. */
    private static final int FISCAL_NAME_LIMIT = 63;

    private final OrderMarksPort marks;
    private final FiscalClassificationLookup classifications;
    private final PaymentFiscalService receipts;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;

    public MarkedOrderReceiptProjection(
            OrderMarksPort marks,
            FiscalClassificationLookup classifications,
            PaymentFiscalService receipts,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock) {
        this.receipts = receipts;
        this.marks = marks;
        this.classifications = classifications;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
    }

    /** What the projection found. */
    public sealed interface Result {

        /** The order holds no marked line; the caller builds the receipt the way it always did. */
        record NotMarked() implements Result {}

        /** A marked line holds fewer scanned units than it has: nothing was built and nothing may be sent. */
        record MarksIncomplete() implements Result {}

        /** The receipt cannot be built lawfully: the payment path cannot carry codes, or a marked line is unclassified. */
        record Unprojectable(String reason) implements Result {}

        /** The receipt lines, codes included. */
        record Lines(List<FiscalReceiptLine> lines) implements Result {}
    }

    /**
     * @param commissionTin the seller's taxpayer number the caller resolved (Click refuses a line without it)
     */
    public Result project(FiscalDocument document, String commissionTin) {
        ReceiptSource source = marks.receiptSource(document.tenantId(), document.orderId());
        if (!source.hasMarkedLine()) {
            return new Result.NotMarked();
        }
        for (ReceiptLine line : source.lines()) {
            if (line.marked() && line.marks().size() < line.requiredMarks()) {
                return new Result.MarksIncomplete();
            }
        }
        if (!receipts.carriesMarkingCodes(document.providerType())) {
            // A provider whose receipt has no field for a marking code (Payme's detail object has none;
            // PaymeReceiptDetail refuses a marked line for the same reason). The safety net behind the
            // payment-options exclusion, and it refuses before anything is decrypted.
            log.warn(
                    "Fiscal document {} for order {} carries marked goods and its provider cannot carry codes; refusing",
                    document.id(),
                    document.orderId());
            return new Result.Unprojectable("MARKING_CODES_UNSUPPORTED");
        }

        Set<UUID> variants = source.lines().stream().map(ReceiptLine::variantId).collect(Collectors.toSet());
        Map<UUID, FiscalClassificationFacts> facts =
                classifications.classifications(document.tenantId(), source.brandId(), variants);

        List<FiscalReceiptLine> lines = new ArrayList<>(source.lines().size());
        int disclosed = 0;
        for (ReceiptLine line : source.lines()) {
            FiscalClassificationFacts classification = facts.get(line.variantId());
            if (line.marked() && !hasClassification(classification)) {
                log.warn(
                        "Order {} has a marked line with no complete fiscal classification; not building its receipt",
                        document.orderId());
                return new Result.Unprojectable("MARKED_LINE_UNCLASSIFIED");
            }
            List<String> codes = line.marked() ? reveal(document.tenantId(), line.marks()) : List.of();
            disclosed += codes.size();
            lines.add(toReceiptLine(line, classification, codes, source.currency(), commissionTin));
        }
        recordDisclosure(document, disclosed);
        return new Result.Lines(List.copyOf(lines));
    }

    private static boolean hasClassification(@Nullable FiscalClassificationFacts facts) {
        return facts != null
                && facts.mxikCode() != null
                && !facts.mxikCode().isBlank()
                && facts.packageCode() != null
                && !facts.packageCode().isBlank();
    }

    private List<String> reveal(UUID tenantId, List<MarkRef> refs) {
        List<String> codes = new ArrayList<>(refs.size());
        for (MarkRef ref : refs.stream()
                .sorted(java.util.Comparator.comparingInt(MarkRef::sequence))
                .toList()) {
            codes.add(protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(ref.protectedCode()),
                    new RecordRef(OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, ref.markId()),
                    PURPOSE));
        }
        return codes;
    }

    private static FiscalReceiptLine toReceiptLine(
            ReceiptLine line,
            @Nullable FiscalClassificationFacts classification,
            List<String> codes,
            String currency,
            String commissionTin) {
        int quantity = line.marked() ? line.requiredMarks() : wholeQuantityOrOne(line.quantity());
        long total = line.finalAmountMinor();
        // Quantity times unit price must equal the line's own amount exactly, and a total that does not
        // divide evenly is carried by a line discount, which both providers take as a line-level amount.
        long unit = Math.floorDiv(total + quantity - 1, quantity);
        long discount = unit * quantity - total;
        String fiscalName = fiscalNameOf(line, classification);
        String mxik = classification == null ? null : classification.mxikCode();
        String packageCode = classification == null ? null : classification.packageCode();
        // A marked line is known to be classified (project() refused otherwise); an unmarked one with no
        // classification carries the same visibly synthetic placeholders the aggregate line does.
        String resolvedMxik = mxik == null || mxik.isBlank() ? PLACEHOLDER_MXIK : mxik;
        String resolvedPackage = packageCode == null || packageCode.isBlank() ? PLACEHOLDER_PACKAGE : packageCode;
        Integer unitCode = classification == null ? null : classification.fiscalUnitCode();
        return new FiscalReceiptLine(
                fiscalName,
                resolvedMxik,
                resolvedPackage,
                unitCode == null ? null : unitCode.longValue(),
                quantity,
                new SomAmount(unit, currency),
                new SomAmount(line.taxAmountMinor(), currency),
                vatPercentOf(total, line.taxAmountMinor()),
                discount > 0 ? new SomAmount(discount, currency) : null,
                classification == null ? null : classification.barcode(),
                codes,
                commissionTin,
                null);
    }

    private static String fiscalNameOf(ReceiptLine line, @Nullable FiscalClassificationFacts classification) {
        String name = classification != null
                        && classification.fiscalName() != null
                        && !classification.fiscalName().isBlank()
                ? classification.fiscalName()
                : line.productName();
        return name.length() <= FISCAL_NAME_LIMIT ? name : name.substring(0, FISCAL_NAME_LIMIT);
    }

    /**
     * A quantity both providers can carry: a whole number of at least one. A fractional quantity (a weighed
     * or split portion) is sent as one line at the line's whole amount, which is the only honest reading
     * of an amount the platform has already settled; the real unit-of-measure mapping is ADR 0166's.
     */
    private static int wholeQuantityOrOne(BigDecimal quantity) {
        BigDecimal stripped = quantity.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.signum() > 0 && stripped.compareTo(BigDecimal.valueOf(10_000)) <= 0) {
            return stripped.intValueExact();
        }
        return 1;
    }

    /**
     * The whole percent both providers type the rate as, derived from the quote's recorded tax share and the
     * line's net amount. Zero when the line carries no tax.
     */
    public static int vatPercentOf(long totalMinor, long taxMinor) {
        long net = totalMinor - taxMinor;
        if (taxMinor <= 0 || net <= 0) {
            return 0;
        }
        return (int) ((taxMinor * 100 + net / 2) / net);
    }

    private void recordDisclosure(FiscalDocument document, int disclosed) {
        if (disclosed == 0) {
            return;
        }
        // One fact per disclosure: the document, the order and how many, never a code. The actor is the
        // system principal that decrypts for a receipt; ADR 0027 asks who and why, and this is both.
        audit.record(AuditFact.of("payments.document.marking_codes_disclosed", AuditClass.BUSINESS)
                .by(ActorRef.systemJob("payments-fiscal"))
                .at(ResourceScope.tenant(document.tenantId()))
                .target("fiscal_document", document.id())
                .because(PURPOSE)
                .changed(ChangeDocuments.created(
                        Map.of("orderId", document.orderId().toString(), "codeCount", disclosed)))
                .correlatedBy(document.id().toString())
                .occurredAt(clock.instant())
                .build());
    }
}
