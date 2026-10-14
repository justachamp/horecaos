package uz.horecaos.platform.payments.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
import uz.horecaos.platform.catalog.api.FiscalVatRate;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderMarksPort.MarkRef;
import uz.horecaos.platform.ordering.api.OrderReceiptSource;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.Line;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.Option;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.ReceiptOrder;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.Allocation;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.Group;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.Piece;
import uz.horecaos.platform.payments.application.ReceiptLineAllocator.UnitPricing;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.SomAmount;

/**
 * The receipt of an order, built from the order (ADR 0166 Decisions 6 and 9, ADR 0038, ADR 0161).
 *
 * <p>This is the real line builder, and it is the only one. It replaced a one-line stand-in for the
 * order total that was named and coded as visibly fake, then refused outside a sandbox, and is now deleted:
 * what a tenant files with the tax authority is what the customer bought, or nothing.
 *
 * <p><strong>One line per thing billed.</strong> Each priced item, each priced modifier option the item
 * carries, and the delivery fee, each with the tenant's own fiscal name, ИКПУ, package code and unit
 * code from its classification, a count, a unit price, a whole-percent VAT rate and the VAT amount. The
 * delivery fee is an ordinary item line of kind {@link FiscalReceiptLine.Kind#DELIVERY_FEE}: Payme's
 * {@code shipping} carries no code, no package code and no VAT percent, so a fee sent there would lose the
 * classification the catalog spends a validator rule on (ADR 0038).
 *
 * <p><strong>What it will not do is guess.</strong> A node with no complete classification, a tax rule
 * that is not a whole percent or not VAT-inclusive, a weighed line that was never weighed, modifier prices
 * an old order never itemised, or lines that do not add up to what the provider was asked to collect, each
 * produce a {@link Result.Blocked} naming the obstacle and nothing else: no placeholder code, no rounded
 * rate, no line absorbing the difference. A document that cannot be built is {@code BLOCKED} with a reason
 * an operator can act on, and a blocked document is recoverable where a filed receipt is not.
 *
 * <p><strong>The arithmetic is {@link ReceiptLineAllocator}'s</strong> and is pure: the discounts spread
 * over the pieces, the points redemption as a per-line discount (ADR 0046), the VAT, and the unit price that
 * makes a decimal count add up exactly. This class gathers the figures and the classifications, runs it,
 * and checks the answer against the payment.
 *
 * <p><strong>Marked goods</strong> (ADR 0161): a marked line carries one scanned code per unit, in the order
 * they were scanned, and the codes are decrypted here, once per document, under a recorded purpose, and
 * only when everything else has already been built and the payment path can carry them. A code is never
 * decrypted for a document that cannot be sent. The audit fact names the document, the order and how many,
 * never a code.
 */
@Service
public class ReceiptLineBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReceiptLineBuilder.class);

    /** The purpose the codes are decrypted under; ADR 0029 records it beside the reveal. */
    public static final String PURPOSE = "fiscal-receipt";

    /** Click caps {@code Name} at 63 characters. */
    static final int FISCAL_NAME_LIMIT = 63;

    /** The unit a weighed line's count is in, appended to its name so the receipt says what the count means. */
    private static final String WEIGHT_UNIT = "kg";

    private static final Pattern NAMES_A_WEIGHT_UNIT = Pattern.compile("(?iu).*(\\bkg\\b|кг|\\bkilogram).*");

    private final OrderReceiptSource orders;
    private final OrderMarksPort marks;
    private final FiscalClassificationLookup classifications;
    private final ReceiptPaymentFacts payments;
    private final PaymentFiscalService receipts;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;

    public ReceiptLineBuilder(
            OrderReceiptSource orders,
            OrderMarksPort marks,
            FiscalClassificationLookup classifications,
            ReceiptPaymentFacts payments,
            PaymentFiscalService receipts,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock) {
        this.orders = orders;
        this.marks = marks;
        this.classifications = classifications;
        this.payments = payments;
        this.receipts = receipts;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
    }

    /** Why a receipt cannot be built, as a class of obstacle the document's block reason is chosen from. */
    public enum Obstacle {

        /** A priced node has no complete classification (ИКПУ, package code, unit code, fiscal name). */
        CLASSIFICATION,

        /** The tax rule cannot be stated on a receipt: not a whole percent, not inclusive, or not recorded. */
        TAX,

        /** The order cannot be made to add up to the payment, or a figure a line needs was never recorded. */
        RECONCILIATION
    }

    /** What the builder found. */
    public sealed interface Result {

        /** A marked line holds fewer scanned units than it has: nothing was built and nothing may be sent. */
        record MarksIncomplete() implements Result {}

        /** The payment path cannot carry what the order holds (a marked good on a provider with no code field). */
        record Unprojectable(String reason) implements Result {}

        /**
         * The receipt cannot be built lawfully or arithmetically.
         *
         * @param detail a short code naming what is wrong. Identifiers and codes only: never a name, an amount
         *     of a customer's or a note, because it reaches a log line
         */
        record Blocked(Obstacle obstacle, String detail) implements Result {}

        /** The receipt lines, codes included. */
        record Lines(List<FiscalReceiptLine> lines) implements Result {}
    }

    /**
     * @param sellerTin the taxpayer number the document was opened under, which Click files every line under
     */
    public Result build(FiscalDocument document, String sellerTin) {
        UUID tenantId = document.tenantId();
        ReceiptOrder order = orders.find(tenantId, document.orderId()).orElse(null);
        if (order == null) {
            return blocked(document, Obstacle.RECONCILIATION, "ORDER_UNREADABLE");
        }

        // ---- the tax rule, stated once for the goods
        OrderReceiptSource.Tax tax = order.tax();
        if (tax == null) {
            return blocked(document, Obstacle.TAX, "QUOTE_TAX_NOT_RECORDED");
        }
        if (!tax.inclusive()) {
            return blocked(document, Obstacle.TAX, "TAX_NOT_INCLUSIVE");
        }
        int goodsVatPercent;
        try {
            goodsVatPercent = FiscalVatRate.wholePercentOf(tax.rateBasisPoints());
        } catch (FiscalVatRate.UnrepresentableVatRate unrepresentable) {
            return blocked(document, Obstacle.TAX, "VAT_RATE_NOT_A_WHOLE_PERCENT");
        }

        // ---- marks first: they can stop everything, and nothing is decrypted for a document that cannot go
        Map<UUID, OrderMarksPort.ReceiptLine> markedLines = new LinkedHashMap<>();
        OrderMarksPort.ReceiptSource markSource = marks.receiptSource(tenantId, document.orderId());
        for (OrderMarksPort.ReceiptLine line : markSource.lines()) {
            if (line.marked()) {
                markedLines.put(line.lineId(), line);
            }
        }
        for (OrderMarksPort.ReceiptLine line : markedLines.values()) {
            if (line.marks().size() < line.requiredMarks()) {
                return new Result.MarksIncomplete();
            }
        }
        if (!markedLines.isEmpty() && !receipts.carriesMarkingCodes(document.providerType())) {
            // A provider whose receipt has no field for a marking code (Payme's detail object has none;
            // PaymeReceiptDetail refuses a marked line for the same reason). The safety net behind the
            // payment-options exclusion, and it refuses before anything is decrypted.
            log.warn(
                    "Fiscal document {} for order {} carries marked goods and its provider cannot carry codes; refusing",
                    document.id(),
                    document.orderId());
            return new Result.Unprojectable("MARKING_CODES_UNSUPPORTED");
        }

        // ---- the pieces and the figures the order stored
        List<Piece> pieces = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        Map<String, PieceFacts> facts = new LinkedHashMap<>();
        long itemDiscounts = 0;
        long linesFinal = 0;
        for (Line line : order.lines()) {
            if (line.unitemisedModifiersMinor() > 0) {
                return blocked(document, Obstacle.RECONCILIATION, "MODIFIER_PRICES_NOT_ITEMISED");
            }
            if (line.catchweight() != null && !line.catchweight().reconciled()) {
                // ADR 0137: a weighed line's amount is provisional until it is weighed, and a receipt is
                // not filed on a figure the platform has not yet settled.
                return blocked(document, Obstacle.RECONCILIATION, "CATCHWEIGHT_NOT_RECONCILED");
            }
            long optionsMinor =
                    line.options().stream().mapToLong(Option::amountMinor).sum();
            long variantMinor = line.baseMinor() - optionsMinor;
            long itemDiscount = line.baseMinor() - line.finalMinor();
            if (variantMinor < 0 || itemDiscount < 0) {
                return blocked(document, Obstacle.RECONCILIATION, "LINE_DOES_NOT_ADD_UP");
            }
            itemDiscounts += itemDiscount;
            linesFinal += line.finalMinor();

            String group = "L:" + line.lineId();
            groups.add(new Group(group, itemDiscount, line.taxMinor()));
            OrderMarksPort.ReceiptLine marked = markedLines.get(line.lineId());

            String variantKey = group + ":V";
            pieces.add(new Piece(variantKey, group, variantMinor, true));
            facts.put(variantKey, PieceFacts.variant(line, marked));
            for (Option option : line.options()) {
                String optionKey = group + ":O:" + option.optionId();
                // The same option twice on a line is two adjustments with one id; one piece, summed.
                if (facts.containsKey(optionKey)) {
                    int at = indexOf(pieces, optionKey);
                    Piece earlier = pieces.get(at);
                    pieces.set(at, new Piece(optionKey, group, earlier.grossMinor() + option.amountMinor(), true));
                    continue;
                }
                pieces.add(new Piece(optionKey, group, option.amountMinor(), true));
                facts.put(optionKey, PieceFacts.option(line, option));
            }
        }
        if (order.feeMinor() > 0) {
            pieces.add(new Piece("FEE", "FEE", order.feeMinor(), false));
            groups.add(new Group("FEE", 0, 0));
            facts.put("FEE", PieceFacts.fee());
        }

        long orderDiscount = order.discountMinor() - itemDiscounts;
        if (orderDiscount < 0 || linesFinal - orderDiscount + order.feeMinor() != order.totalMinor()) {
            // The order's own parts do not reconcile to its total (an amendment that did not restate a
            // figure). A receipt built on it would not equal the sale.
            return blocked(document, Obstacle.RECONCILIATION, "ORDER_DOES_NOT_ADD_UP");
        }

        // ---- the classifications, all of them, before any arithmetic is trusted
        Set<UUID> nodeIds = new HashSet<>();
        for (Piece piece : pieces) {
            PieceFacts piece0 = factsOf(facts, piece.key());
            if (piece.grossMinor() > 0 && piece0.nodeId() != null) {
                nodeIds.add(piece0.nodeId());
            }
        }
        Map<UUID, FiscalClassificationFacts> classified =
                classifications.classifications(tenantId, order.brandId(), nodeIds);
        FiscalClassificationFacts feeClassification = order.feeMinor() > 0
                ? classifications.deliveryFee(tenantId, order.brandId()).orElse(null)
                : null;
        int unclassified = 0;
        for (Piece piece : pieces) {
            if (piece.grossMinor() == 0) {
                continue;
            }
            PieceFacts piece0 = factsOf(facts, piece.key());
            FiscalClassificationFacts found =
                    piece0.nodeId() == null ? feeClassification : classified.get(piece0.nodeId());
            if (!complete(found)) {
                unclassified++;
            }
        }
        if (unclassified > 0) {
            log.warn(
                    "Order {} has {} priced node(s) with no complete fiscal classification; not building its receipt",
                    document.orderId(),
                    unclassified);
            return blocked(document, Obstacle.CLASSIFICATION, "CLASSIFICATION_MISSING");
        }

        // ---- the arithmetic
        List<Allocation> allocations;
        try {
            allocations = ReceiptLineAllocator.allocate(new ReceiptLineAllocator.Input(
                    pieces,
                    groups,
                    orderDiscount,
                    payments.pointsRedeemedMinor(tenantId, document.orderId()),
                    tax.rateBasisPoints()));
        } catch (IllegalArgumentException cannotAllocate) {
            return blocked(document, Obstacle.RECONCILIATION, "ALLOCATION_REFUSED");
        }

        long receiptTotal = allocations.stream().mapToLong(Allocation::netMinor).sum();
        OptionalLong collected = payments.providerAmountMinor(tenantId, document);
        if (collected.isEmpty() || collected.getAsLong() != receiptTotal) {
            // The lines do not equal what the provider was asked to collect: a second money tender on the
            // order, or a payment restated after the order. Filing it would put a receipt on record that
            // disagrees with the payment it is evidence of.
            return blocked(document, Obstacle.RECONCILIATION, "RECEIPT_TOTAL_MISMATCH");
        }

        // ---- the lines
        String currency = order.currency();
        List<Allocation> toFile =
                allocations.stream().filter(a -> a.grossMinor() > 0).toList();
        if (toFile.isEmpty()) {
            return blocked(document, Obstacle.RECONCILIATION, "NOTHING_TO_FILE");
        }
        List<FiscalReceiptLine> lines = new ArrayList<>(toFile.size());
        int disclosed = 0;
        for (Allocation allocation : toFile) {
            PieceFacts piece = factsOf(facts, allocation.key());
            FiscalClassificationFacts classification =
                    piece.nodeId() == null ? feeClassification : classified.get(piece.nodeId());
            Objects.requireNonNull(classification, "every piece with a value was classified above");

            BigDecimal quantity = piece.quantity();
            UnitPricing pricing =
                    ReceiptLineAllocator.unitPriceOf(allocation.grossMinor(), allocation.netMinor(), quantity);
            List<String> codes = piece.marked() == null ? List.of() : reveal(tenantId, piece.marked());
            disclosed += codes.size();
            lines.add(new FiscalReceiptLine(
                    fiscalName(classification, piece.weighed()),
                    Objects.requireNonNull(classification.mxikCode()),
                    Objects.requireNonNull(classification.packageCode()),
                    Objects.requireNonNull(classification.fiscalUnitCode()).longValue(),
                    quantity,
                    new SomAmount(pricing.unitMinor(), currency),
                    new SomAmount(allocation.taxMinor(), currency),
                    piece.nodeId() == null ? 0 : goodsVatPercent,
                    pricing.discountMinor() > 0 ? new SomAmount(pricing.discountMinor(), currency) : null,
                    classification.barcode(),
                    codes,
                    sellerTin,
                    null,
                    piece.nodeId() == null ? FiscalReceiptLine.Kind.DELIVERY_FEE : FiscalReceiptLine.Kind.ITEM));
        }
        recordDisclosure(document, disclosed);
        return new Result.Lines(List.copyOf(lines));
    }

    // ------------------------------------------------------------------ pieces

    /**
     * What the builder needs to know about one piece beyond its value.
     *
     * @param nodeId the classified node (a variant or a modifier option), or null for the delivery fee
     * @param quantity the count the receipt states
     * @param weighed whether the count is a weight in kilograms
     * @param marked the line's marks when this piece is a marked variant, else null
     */
    private record PieceFacts(
            @Nullable UUID nodeId, BigDecimal quantity, boolean weighed, OrderMarksPort.@Nullable ReceiptLine marked) {

        static PieceFacts variant(Line line, OrderMarksPort.@Nullable ReceiptLine marked) {
            if (line.catchweight() != null) {
                // The count of a weighed line is its weight in kilograms; its amount is the weighed total.
                BigDecimal kilograms = BigDecimal.valueOf(
                                Objects.requireNonNull(line.catchweight().actualWeightGrams()), 3)
                        .stripTrailingZeros();
                return new PieceFacts(line.variantId(), kilograms, true, marked);
            }
            // A marked line is billed per scanned unit: one code each, in the order they were scanned.
            BigDecimal quantity = marked != null ? BigDecimal.valueOf(marked.requiredMarks()) : line.quantity();
            return new PieceFacts(line.variantId(), quantity, false, marked);
        }

        static PieceFacts option(Line line, Option option) {
            // An option is charged once per unit of the line it is on.
            return new PieceFacts(option.optionId(), line.quantity(), false, null);
        }

        static PieceFacts fee() {
            return new PieceFacts(null, BigDecimal.ONE, false, null);
        }
    }

    private static PieceFacts factsOf(Map<String, PieceFacts> facts, String key) {
        return Objects.requireNonNull(facts.get(key), "every piece was registered with its facts");
    }

    private static int indexOf(List<Piece> pieces, String key) {
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).key().equals(key)) {
                return i;
            }
        }
        throw new IllegalStateException("No piece " + key);
    }

    // ----------------------------------------------------------- classification

    private static boolean complete(@Nullable FiscalClassificationFacts facts) {
        return facts != null
                && notBlank(facts.mxikCode())
                && notBlank(facts.packageCode())
                && notBlank(facts.fiscalName())
                && facts.fiscalUnitCode() != null;
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /**
     * The name a receipt states: the tenant's fiscal name, which carries its own unit of measure for a
     * piece, and for a weighed line the unit its count is in, so that {@code 1.04} reads as kilograms.
     */
    static String fiscalName(FiscalClassificationFacts classification, boolean weighed) {
        String name = Objects.requireNonNull(classification.fiscalName()).strip();
        if (weighed && !NAMES_A_WEIGHT_UNIT.matcher(name).matches()) {
            String suffix = ", " + WEIGHT_UNIT;
            String base = name.length() + suffix.length() <= FISCAL_NAME_LIMIT
                    ? name
                    : name.substring(0, FISCAL_NAME_LIMIT - suffix.length());
            return base + suffix;
        }
        return name.length() <= FISCAL_NAME_LIMIT ? name : name.substring(0, FISCAL_NAME_LIMIT);
    }

    // -------------------------------------------------------------------- marks

    private List<String> reveal(UUID tenantId, OrderMarksPort.ReceiptLine line) {
        List<String> codes = new ArrayList<>(line.marks().size());
        for (MarkRef ref : line.marks().stream()
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

    private Result.Blocked blocked(FiscalDocument document, Obstacle obstacle, String detail) {
        // Identifiers and a code only (ADR 0029): which document, which order, what is wrong.
        log.warn(
                "Fiscal document {} for order {} cannot have its receipt built: {} ({})",
                document.id(),
                document.orderId(),
                detail,
                obstacle);
        return new Result.Blocked(obstacle, detail);
    }
}
