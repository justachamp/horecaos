package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup.FiscalClassificationFacts;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderMarksPort.MarkRef;
import uz.horecaos.platform.ordering.api.OrderReceiptSource;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.Catchweight;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.Line;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.Option;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.ReceiptOrder;
import uz.horecaos.platform.ordering.api.OrderReceiptSource.Tax;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.application.ReceiptLineBuilder;
import uz.horecaos.platform.payments.application.ReceiptLineBuilder.Obstacle;
import uz.horecaos.platform.payments.application.ReceiptLineBuilder.Result;
import uz.horecaos.platform.payments.application.ReceiptPaymentFacts;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalDocumentType;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.support.TestProtection;

/**
 * The receipt of an order, built from the order (ADR 0166 Decisions 6 and 9, ADR 0038, ADR 0161).
 *
 * <p>The properties a green «it built some lines» test would miss, each stated as what would still be true
 * if the builder were broken: the receipt equals the payment to the minor unit; no line is ever a
 * placeholder; an order it cannot build is blocked with a named obstacle and <em>nothing</em> else; the
 * delivery fee is a line of its own kind and not Payme's shipping; a marked order's codes are decrypted only
 * for a document that can be sent, and never reach an audit fact.
 */
class ReceiptLineBuilderTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID PLOV = UUID.randomUUID();
    private static final UUID LAMB = UUID.randomUUID();
    private static final UUID VODKA = UUID.randomUUID();
    private static final UUID CHEESE = UUID.randomUUID();
    private static final UUID SAUCE = UUID.randomUUID();
    private static final String TIN = "123456789";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC);

    private static final String CODE_A = "010460000000001521FiscalSerialAAA1";
    private static final String CODE_B = "010460000000001521FiscalSerialBBB2";
    private static final String PLACEHOLDER_MXIK = "00000000000000000";

    private CountingProtection protection;
    private Map<UUID, FiscalClassificationFacts> classes;
    private Optional<FiscalClassificationFacts> feeClass;
    private OrderMarksPort.ReceiptSource marks;
    private PaymentFiscalService receipts;
    private List<AuditFact> audit;
    private @Nullable ReceiptOrder order;
    private OptionalLong paid = OptionalLong.empty();
    private long redeemed;

    @BeforeEach
    void wire() {
        protection = new CountingProtection(TestProtection.envelope());
        classes = new HashMap<>();
        classes.put(PLOV, facts("10901001001000000", "1500123", 796, "Plov"));
        classes.put(CHEESE, facts("04001001001000000", "1500456", 796, "Extra cheese"));
        classes.put(SAUCE, facts("09001001001000000", "1500789", 796, "Sauce"));
        classes.put(LAMB, facts("10101001001000000", "1500321", 166, "Lamb"));
        classes.put(
                VODKA,
                new FiscalClassificationFacts("22030000000000000", "123456", 796, "Vodka 0.5l", "4600000000015", true));
        feeClass = Optional.of(facts("10701001001000000", "7000001", 796, "Delivery"));
        marks = new OrderMarksPort.ReceiptSource(BRAND, "UZS", List.of());
        receipts = mock(PaymentFiscalService.class);
        when(receipts.carriesMarkingCodes(PaymentProviderType.CLICK)).thenReturn(true);
        when(receipts.carriesMarkingCodes(PaymentProviderType.PAYME)).thenReturn(false);
        audit = new ArrayList<>();
        redeemed = 0;
    }

    // ----------------------------------------------------------------- the lines

    @Test
    @DisplayName("a dish with an option and a delivery fee is three lines, each with its own classification")
    void aDishItsOptionAndTheFeeAreThreeLines() {
        order = order(
                List.of(line(PLOV, "Plov", "1", 13_000, 13_000, 1_393, List.of(option(CHEESE, 3_000)))), 2_000, 0);
        paid = OptionalLong.of(15_000);

        List<FiscalReceiptLine> lines = lines(build(PaymentProviderType.CLICK));

        assertThat(lines).extracting(FiscalReceiptLine::fiscalName).containsExactly("Plov", "Extra cheese", "Delivery");
        assertThat(lines)
                .extracting(FiscalReceiptLine::mxikCode)
                .containsExactly("10901001001000000", "04001001001000000", "10701001001000000");
        assertThat(lines).extracting(FiscalReceiptLine::packageCode).containsExactly("1500123", "1500456", "7000001");
        assertThat(lines)
                .extracting(FiscalReceiptLine::kind)
                .containsExactly(
                        FiscalReceiptLine.Kind.ITEM, FiscalReceiptLine.Kind.ITEM, FiscalReceiptLine.Kind.DELIVERY_FEE);
        assertThat(lines).extracting(l -> l.netTotal().value()).containsExactly(10_000L, 3_000L, 2_000L);
        assertThat(lines).extracting(FiscalReceiptLine::vatPercent).containsExactly(12, 12, 0);
        assertThat(lines.stream().mapToLong(l -> l.taxAmount().value()).sum())
                .as("the receipt's VAT is the order's, to the minor unit")
                .isEqualTo(1_393);
        assertThat(lines).extracting(FiscalReceiptLine::commissionTin).containsOnly(TIN);
        assertThat(lines).extracting(FiscalReceiptLine::mxikCode).doesNotContain(PLACEHOLDER_MXIK);
    }

    @Test
    @DisplayName("the delivery fee is a line only when the order charged one")
    void noFeeNoFeeLine() {
        order = order(List.of(line(PLOV, "Plov", "1", 15_000, 15_000, 1_607, List.of())), 0, 0);
        paid = OptionalLong.of(15_000);
        feeClass = Optional.empty();

        List<FiscalReceiptLine> lines = lines(build(PaymentProviderType.CLICK));

        assertThat(lines).singleElement().satisfies(l -> assertThat(l.kind()).isEqualTo(FiscalReceiptLine.Kind.ITEM));
    }

    @Test
    @DisplayName("an item discount is spread over the dish and its option, so neither line is billed what it was not")
    void anItemDiscountIsSpreadOverTheLine() {
        // 40 000 of dish and sauce, 4 000 off the line by a promotion.
        order = order(List.of(line(PLOV, "Plov", "1", 40_000, 36_000, 3_857, List.of(option(SAUCE, 10_000)))), 0, 0);
        paid = OptionalLong.of(36_000);

        List<FiscalReceiptLine> lines = lines(build(PaymentProviderType.CLICK));

        assertThat(lines).extracting(l -> l.netTotal().value()).containsExactly(27_000L, 9_000L);
        assertThat(lines).extracting(l -> discountOf(l)).containsExactly(3_000L, 1_000L);
        assertThat(lines.stream().mapToLong(l -> l.taxAmount().value()).sum()).isEqualTo(3_857);
    }

    @Test
    @DisplayName("an order-level discount that no line carries is spread over the goods and never the delivery fee")
    void anOrderDiscountIsSpreadOverTheGoods() {
        order = order(
                List.of(
                        line(PLOV, "Plov", "1", 50_000, 50_000, 5_357, List.of()),
                        line(LAMB, "Lamb", "1", 30_000, 30_000, 3_214, List.of())),
                8_000,
                8_000);
        // Goods 80 000, discount 8 000, fee 8 000: the customer pays 80 000.
        paid = OptionalLong.of(80_000);
        classes.put(LAMB, facts("10101001001000000", "1500321", 796, "Lamb"));

        List<FiscalReceiptLine> lines = lines(build(PaymentProviderType.CLICK));

        assertThat(lines).extracting(FiscalReceiptLine::fiscalName).containsExactly("Plov", "Lamb", "Delivery");
        assertThat(lines).extracting(l -> l.netTotal().value()).containsExactly(45_000L, 27_000L, 8_000L);
        assertThat(lines.get(2).discount())
                .as("the fee carries no share of any discount")
                .isNull();
    }

    @Test
    @DisplayName(
            "ADR 0046's worked order: points are a per-line discount on the food, the receipt equals the Click payment, VAT 7 714")
    void pointsAreADiscountOnTheFood() {
        order = order(List.of(line(PLOV, "Plov", "1", 84_000, 84_000, 9_000, List.of())), 10_000, 0);
        paid = OptionalLong.of(82_000);
        redeemed = 12_000;

        List<FiscalReceiptLine> lines = lines(build(PaymentProviderType.CLICK));

        FiscalReceiptLine food = lines.get(0);
        assertThat(food.unitPrice().value()).isEqualTo(84_000);
        assertThat(discountOf(food)).isEqualTo(12_000);
        assertThat(food.netTotal().value()).isEqualTo(72_000);
        assertThat(food.taxAmount().value()).as("round(72 000 x 1200 / 11 200)").isEqualTo(7_714);
        FiscalReceiptLine fee = lines.get(1);
        assertThat(fee.netTotal().value()).isEqualTo(10_000);
        assertThat(fee.discount()).isNull();
        assertThat(lines.stream().mapToLong(l -> l.netTotal().value()).sum()).isEqualTo(82_000);
    }

    // ------------------------------------------------------- weighed lines

    @Test
    @DisplayName(
            "a weighed line is billed by the kilogram: a decimal count, the weighed total, and the unit in the name")
    void aWeighedLineCarriesADecimalCount() {
        // 1.040 kg of lamb for 83 199: weighed at the scale, the total is what the customer pays.
        order = order(List.of(weighed(LAMB, "Lamb", 2, 83_199, 8_914, 1_040)), 0, 0);
        paid = OptionalLong.of(83_199);

        FiscalReceiptLine line = lines(build(PaymentProviderType.CLICK)).getFirst();

        assertThat(line.quantity()).isEqualByComparingTo("1.04");
        assertThat(line.fiscalName())
                .as("the count is kilograms, and the receipt says so")
                .isEqualTo("Lamb, kg");
        assertThat(line.unitPrice().value()).isEqualTo(80_000);
        assertThat(discountOf(line))
                .as("the rounding to a unit price a provider can add up exactly, stated as discount")
                .isEqualTo(1);
        assertThat(line.netTotal().value()).as("the weighed total").isEqualTo(83_199);
        assertThat(line.unitCode()).isEqualTo(166L);
    }

    @Test
    @DisplayName("half a kilogram is a count of 0.5 at a unit price that makes the line add up")
    void halfAKilogram() {
        order = order(List.of(weighed(LAMB, "Lamb", 1, 40_000, 4_286, 500)), 0, 0);
        paid = OptionalLong.of(40_000);

        FiscalReceiptLine line = lines(build(PaymentProviderType.CLICK)).getFirst();

        assertThat(line.quantity()).isEqualByComparingTo("0.5");
        assertThat(line.unitPrice().value()).isEqualTo(80_000);
        assertThat(line.discount()).isNull();
        assertThat(line.lineTotal().value()).isEqualTo(40_000);
    }

    @Test
    @DisplayName("a name that already says kilograms is not given the unit twice, and the 63-character cap holds")
    void theUnitIsAddedOnceAndTheNameFits() {
        classes.put(LAMB, facts("10101001001000000", "1500321", 166, "Lamb, kg"));
        order = order(List.of(weighed(LAMB, "Lamb", 1, 40_000, 4_286, 500)), 0, 0);
        paid = OptionalLong.of(40_000);
        assertThat(lines(build(PaymentProviderType.CLICK)).getFirst().fiscalName())
                .isEqualTo("Lamb, kg");

        String sixtyThree = "x".repeat(63);
        classes.put(LAMB, facts("10101001001000000", "1500321", 166, sixtyThree));
        String name = lines(build(PaymentProviderType.CLICK)).getFirst().fiscalName();
        assertThat(name).hasSize(63).endsWith(", kg");
    }

    @Test
    @DisplayName("a weighed line that was never weighed blocks the receipt: it is not filed on a provisional amount")
    void anUnweighedLineBlocks() {
        Line provisional = new Line(
                UUID.randomUUID(),
                1,
                LAMB,
                "Lamb",
                BigDecimal.ONE,
                80_000,
                80_000,
                8_571,
                new Catchweight(null),
                List.of(),
                0);
        order = order(List.of(provisional), 0, 0);
        paid = OptionalLong.of(80_000);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.RECONCILIATION, "CATCHWEIGHT_NOT_RECONCILED");
    }

    // ------------------------------------------------------- what it will not do

    @Test
    @DisplayName("a priced node with no complete classification blocks the receipt: no placeholder code is written")
    void anUnclassifiedNodeBlocks() {
        order = order(
                List.of(line(PLOV, "Plov", "1", 13_000, 13_000, 1_393, List.of(option(CHEESE, 3_000)))), 2_000, 0);
        paid = OptionalLong.of(15_000);
        classes.remove(CHEESE);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.CLASSIFICATION, "CLASSIFICATION_MISSING");
    }

    @ParameterizedTest(name = "a classification missing its {0} is as good as none")
    @CsvSource({"ИКПУ", "package code", "unit code", "fiscal name"})
    void aClassificationMissingAnyOfItsFourFieldsBlocks(String missing) {
        order = order(List.of(line(PLOV, "Plov", "1", 15_000, 15_000, 1_607, List.of())), 0, 0);
        paid = OptionalLong.of(15_000);
        classes.put(
                PLOV,
                switch (missing) {
                    case "ИКПУ" -> new FiscalClassificationFacts(null, "1500123", 796, "Plov", null, false);
                    case "package code" ->
                        new FiscalClassificationFacts("10901001001000000", " ", 796, "Plov", null, false);
                    case "unit code" ->
                        new FiscalClassificationFacts("10901001001000000", "1500123", null, "Plov", null, false);
                    default -> new FiscalClassificationFacts("10901001001000000", "1500123", 796, null, null, false);
                });

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.CLASSIFICATION, "CLASSIFICATION_MISSING");
    }

    @Test
    @DisplayName(
            "a delivery fee with no classification blocks the receipt, since Payme's shipping could not carry it either")
    void anUnclassifiedDeliveryFeeBlocks() {
        order = order(List.of(line(PLOV, "Plov", "1", 13_000, 13_000, 1_393, List.of())), 2_000, 0);
        paid = OptionalLong.of(15_000);
        feeClass = Optional.empty();

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.CLASSIFICATION, "CLASSIFICATION_MISSING");
    }

    @ParameterizedTest(name = "{0} bp, inclusive={1}: {2}")
    @CsvSource({
        "1250, true, VAT_RATE_NOT_A_WHOLE_PERCENT",
        "1200, false, TAX_NOT_INCLUSIVE",
    })
    @DisplayName(
            "a rate that is not a whole percent, or prices that were not VAT-inclusive, cannot be stated: no rounding")
    void aTaxRuleThatCannotBeStatedBlocks(int basisPoints, boolean inclusive, String detail) {
        order = withTax(
                order(List.of(line(PLOV, "Plov", "1", 15_000, 15_000, 1_607, List.of())), 0, 0),
                new Tax(basisPoints, inclusive));
        paid = OptionalLong.of(15_000);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.TAX, detail);
    }

    @Test
    @DisplayName("a quote that recorded no tax rule blocks the receipt: a rate is never guessed from the amounts")
    void anUnrecordedTaxRuleBlocks() {
        order = withTax(order(List.of(line(PLOV, "Plov", "1", 15_000, 15_000, 1_607, List.of())), 0, 0), null);
        paid = OptionalLong.of(15_000);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.TAX, "QUOTE_TAX_NOT_RECORDED");
    }

    @Test
    @DisplayName(
            "modifier prices an older order never itemised cannot be split between the options, so the receipt is not built")
    void unitemisedModifiersBlock() {
        Line legacy = new Line(
                UUID.randomUUID(), 1, PLOV, "Plov", BigDecimal.ONE, 15_000, 15_000, 1_607, null, List.of(), 3_000);
        order = order(List.of(legacy), 0, 0);
        paid = OptionalLong.of(15_000);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.RECONCILIATION, "MODIFIER_PRICES_NOT_ITEMISED");
    }

    @Test
    @DisplayName(
            "a receipt that does not equal what the provider was asked to collect is blocked, and no line absorbs the gap")
    void aReceiptThatDoesNotEqualThePaymentBlocks() {
        order = order(List.of(line(PLOV, "Plov", "1", 14_000, 14_000, 1_500, List.of())), 0, 0);
        paid = OptionalLong.of(15_000);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.RECONCILIATION, "RECEIPT_TOTAL_MISMATCH");
    }

    @Test
    @DisplayName("a document that names no readable payment cannot be checked against one, so it is not built")
    void noPaymentToCheckAgainstBlocks() {
        order = order(List.of(line(PLOV, "Plov", "1", 15_000, 15_000, 1_607, List.of())), 0, 0);
        paid = OptionalLong.empty();

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.RECONCILIATION, "RECEIPT_TOTAL_MISMATCH");
    }

    @Test
    @DisplayName("an order whose own parts do not add up to its total is not built")
    void anOrderThatDoesNotAddUpIsNotBuilt() {
        ReceiptOrder good = order(List.of(line(PLOV, "Plov", "1", 15_000, 15_000, 1_607, List.of())), 0, 0);
        order = new ReceiptOrder(
                good.orderId(),
                good.brandId(),
                good.locationId(),
                good.currency(),
                16_000,
                good.taxMinor(),
                good.discountMinor(),
                good.feeMinor(),
                good.quoteId(),
                good.tax(),
                good.lines());
        paid = OptionalLong.of(15_000);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.RECONCILIATION, "ORDER_DOES_NOT_ADD_UP");
    }

    @Test
    @DisplayName("an order that cannot be read is blocked, not guessed at")
    void anUnreadableOrderBlocks() {
        order = null;
        paid = OptionalLong.of(15_000);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.RECONCILIATION, "ORDER_UNREADABLE");
    }

    @Test
    @DisplayName("an order with nothing to file is blocked: an empty receipt is evidence of nothing")
    void anOrderWithNothingToFileBlocks() {
        order = order(List.of(line(PLOV, "Plov", "1", 0, 0, 0, List.of())), 0, 0);
        paid = OptionalLong.of(0);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.RECONCILIATION, "NOTHING_TO_FILE");
    }

    // ------------------------------------------------------------- marked goods

    @Test
    @DisplayName("a marked line is billed per scanned unit, carrying its codes in the order they were scanned")
    void aMarkedLineCarriesItsCodesInSequence() {
        UUID lineId = UUID.randomUUID();
        order = order(List.of(lineWithId(lineId, VODKA, "Vodka", "2", 200_000, 200_000, 21_429, List.of())), 0, 0);
        paid = OptionalLong.of(200_000);
        // Stored out of order: the sequence, not the storage order, decides.
        marks = marksOf(vodkaMarks(lineId, 2, List.of(mark(2, CODE_B), mark(1, CODE_A))));

        FiscalReceiptLine line = lines(build(PaymentProviderType.CLICK)).getFirst();

        assertThat(line.quantity()).isEqualByComparingTo("2");
        assertThat(line.markingCodes()).containsExactly(CODE_A, CODE_B);
        assertThat(line.barcode()).isEqualTo("4600000000015");
        assertThat(line.mxikCode()).isEqualTo("22030000000000000");
    }

    @Test
    @DisplayName("an incomplete marked line builds nothing and decrypts nothing")
    void incompleteMarksDecryptNothing() {
        UUID lineId = UUID.randomUUID();
        order = order(List.of(lineWithId(lineId, VODKA, "Vodka", "2", 200_000, 200_000, 21_429, List.of())), 0, 0);
        paid = OptionalLong.of(200_000);
        marks = marksOf(vodkaMarks(lineId, 2, List.of(mark(1, CODE_A))));

        assertThat(build(PaymentProviderType.CLICK)).isInstanceOf(Result.MarksIncomplete.class);
        assertThat(protection.reveals.get()).isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("a provider with no field for a code is refused before any code is decrypted")
    void aProviderThatCannotCarryCodesDecryptsNothing() {
        UUID lineId = UUID.randomUUID();
        order = order(List.of(lineWithId(lineId, VODKA, "Vodka", "1", 100_000, 100_000, 10_714, List.of())), 0, 0);
        paid = OptionalLong.of(100_000);
        marks = marksOf(vodkaMarks(lineId, 1, List.of(mark(1, CODE_A))));

        assertThat(build(PaymentProviderType.PAYME))
                .isInstanceOfSatisfying(
                        Result.Unprojectable.class,
                        refused -> assertThat(refused.reason()).isEqualTo("MARKING_CODES_UNSUPPORTED"));
        assertThat(protection.reveals.get()).isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("a marked good with no classification blocks the receipt as an unclassified node, decrypting nothing")
    void aMarkedLineWithNoClassificationDecryptsNothing() {
        UUID lineId = UUID.randomUUID();
        order = order(List.of(lineWithId(lineId, VODKA, "Vodka", "1", 100_000, 100_000, 10_714, List.of())), 0, 0);
        paid = OptionalLong.of(100_000);
        marks = marksOf(vodkaMarks(lineId, 1, List.of(mark(1, CODE_A))));
        classes.remove(VODKA);

        assertBlocked(build(PaymentProviderType.CLICK), Obstacle.CLASSIFICATION, "CLASSIFICATION_MISSING");
        assertThat(protection.reveals.get())
                .as("a coded unit under a placeholder is worse than no receipt, and no code was opened for it")
                .isZero();
    }

    @Test
    @DisplayName(
            "each code is decrypted once, under the receipt purpose, and one fact records the count and never a code")
    void theDisclosureIsRecordedOnceAndNeverCarriesACode() {
        UUID lineId = UUID.randomUUID();
        order = order(
                List.of(
                        lineWithId(lineId, VODKA, "Vodka", "2", 200_000, 200_000, 21_429, List.of()),
                        line(PLOV, "Plov", "1", 25_000, 25_000, 2_679, List.of())),
                0,
                0);
        paid = OptionalLong.of(225_000);
        marks = marksOf(vodkaMarks(lineId, 2, List.of(mark(1, CODE_A), mark(2, CODE_B))));

        build(PaymentProviderType.CLICK);

        assertThat(protection.reveals.get()).isEqualTo(2);
        assertThat(protection.purposes).containsOnly(ReceiptLineBuilder.PURPOSE);
        assertThat(audit).hasSize(1);
        AuditFact fact = audit.get(0);
        assertThat(fact.actionCode()).isEqualTo("payments.document.marking_codes_disclosed");
        assertThat(fact.changeDocument().toString()).contains("codeCount").contains("2");
        assertThat(fact.toString()).doesNotContain("FiscalSerial");
        assertThat(fact.changeDocument().toString()).doesNotContain("FiscalSerial");
    }

    @Test
    @DisplayName(
            "a ciphertext copied onto another mark's row fails to decrypt instead of putting a stranger's code on the receipt")
    void aCodeBoundToAnotherRowIsNotRevealed() {
        UUID lineId = UUID.randomUUID();
        order = order(List.of(lineWithId(lineId, VODKA, "Vodka", "1", 100_000, 100_000, 10_714, List.of())), 0, 0);
        paid = OptionalLong.of(100_000);
        String stolen = protection
                .delegate
                .protect(
                        TENANT,
                        DataClass.FINANCIAL,
                        new RecordRef(OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, UUID.randomUUID()),
                        CODE_A)
                .serialize();
        marks = marksOf(vodkaMarks(lineId, 1, List.of(new MarkRef(UUID.randomUUID(), 1, "04600000000015", stolen))));

        assertThatThrownBy(() -> build(PaymentProviderType.CLICK))
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
        assertThat(audit)
                .as("nothing is recorded for a disclosure that did not happen")
                .isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private Result build(PaymentProviderType provider) {
        OrderReceiptSource source = (tenantId, orderId) -> Optional.ofNullable(order);
        OrderMarksPort marksPort = new OrderMarksPort() {
            @Override
            public List<LineMarkCount> markCounts(UUID tenantId, UUID orderId) {
                return List.of();
            }

            @Override
            public ReceiptSource receiptSource(UUID tenantId, UUID orderId) {
                return marks;
            }
        };
        FiscalClassificationLookup lookup = new FiscalClassificationLookup() {
            @Override
            public Map<UUID, FiscalClassificationFacts> classifications(
                    UUID tenantId, UUID brandId, Set<UUID> priceableIds) {
                return classes;
            }

            @Override
            public Optional<FiscalClassificationFacts> deliveryFee(UUID tenantId, UUID brandId) {
                return feeClass;
            }
        };
        ReceiptPaymentFacts payments = new ReceiptPaymentFacts() {
            @Override
            public OptionalLong providerAmountMinor(UUID tenantId, @Nullable UUID paymentIntentId) {
                return paid;
            }

            @Override
            public long pointsRedeemedMinor(UUID tenantId, UUID orderId) {
                return redeemed;
            }
        };
        return new ReceiptLineBuilder(source, marksPort, lookup, payments, receipts, protection, audit::add, CLOCK)
                .build(document(provider), TIN);
    }

    private static void assertBlocked(Result result, Obstacle obstacle, String detail) {
        assertThat(result)
                .as("the receipt is not built; nothing else is returned")
                .isInstanceOfSatisfying(Result.Blocked.class, blocked -> {
                    assertThat(blocked.obstacle()).isEqualTo(obstacle);
                    assertThat(blocked.detail()).isEqualTo(detail);
                });
    }

    private static List<FiscalReceiptLine> lines(Result result) {
        assertThat(result).as("built").isInstanceOf(Result.Lines.class);
        List<FiscalReceiptLine> lines = ((Result.Lines) result).lines();
        assertThat(lines)
                .as("no placeholder, ever")
                .extracting(FiscalReceiptLine::mxikCode)
                .doesNotContain(PLACEHOLDER_MXIK);
        return lines;
    }

    private static long discountOf(FiscalReceiptLine line) {
        return java.util.Objects.requireNonNull(line.discount(), "the line states a discount")
                .value();
    }

    private static FiscalClassificationFacts facts(String mxik, String packageCode, int unit, String name) {
        return new FiscalClassificationFacts(mxik, packageCode, unit, name, null, false);
    }

    private static Option option(UUID id, long amount) {
        return new Option(id, "option", amount, false);
    }

    private static Line line(UUID variant, String name, String qty, long base, long fin, long tax, List<Option> opts) {
        return lineWithId(UUID.randomUUID(), variant, name, qty, base, fin, tax, opts);
    }

    private static Line lineWithId(
            UUID id, UUID variant, String name, String qty, long base, long fin, long tax, List<Option> opts) {
        return new Line(id, 1, variant, name, new BigDecimal(qty), base, fin, tax, null, opts, 0);
    }

    private static Line weighed(UUID variant, String name, int units, long amount, long tax, int grams) {
        return new Line(
                UUID.randomUUID(),
                1,
                variant,
                name,
                BigDecimal.valueOf(units),
                amount,
                amount,
                tax,
                new Catchweight(grams),
                List.of(),
                0);
    }

    /** An order whose totals are what its lines, discount and fee add up to. */
    private static ReceiptOrder order(List<Line> lines, long fee, long orderDiscount) {
        long itemDiscounts =
                lines.stream().mapToLong(l -> l.baseMinor() - l.finalMinor()).sum();
        long goods = lines.stream().mapToLong(Line::finalMinor).sum();
        return new ReceiptOrder(
                ORDER,
                BRAND,
                UUID.randomUUID(),
                "UZS",
                goods - orderDiscount + fee,
                lines.stream().mapToLong(Line::taxMinor).sum(),
                itemDiscounts + orderDiscount,
                fee,
                UUID.randomUUID(),
                new Tax(1200, true),
                lines);
    }

    private static ReceiptOrder withTax(ReceiptOrder base, @Nullable Tax tax) {
        return new ReceiptOrder(
                base.orderId(),
                base.brandId(),
                base.locationId(),
                base.currency(),
                base.totalMinor(),
                base.taxMinor(),
                base.discountMinor(),
                base.feeMinor(),
                base.quoteId(),
                tax,
                base.lines());
    }

    private static FiscalDocument document(PaymentProviderType provider) {
        return new FiscalDocument(
                UUID.randomUUID(),
                TENANT,
                ORDER,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                provider,
                FiscalDocumentType.SALE,
                null,
                FiscalStatus.PENDING,
                "PENDING",
                "",
                List.of(),
                null,
                1,
                CLOCK.instant());
    }

    private static OrderMarksPort.ReceiptSource marksOf(OrderMarksPort.ReceiptLine... lines) {
        return new OrderMarksPort.ReceiptSource(BRAND, "UZS", List.of(lines));
    }

    private static OrderMarksPort.ReceiptLine vodkaMarks(UUID lineId, int units, List<MarkRef> refs) {
        return new OrderMarksPort.ReceiptLine(
                lineId, VODKA, "Vodka", BigDecimal.valueOf(units), 100_000L * units, 0L, true, units, refs);
    }

    private MarkRef mark(int sequence, String code) {
        UUID id = UUID.randomUUID();
        String envelope = protection
                .delegate
                .protect(
                        TENANT,
                        DataClass.FINANCIAL,
                        new RecordRef(OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, id),
                        code)
                .serialize();
        return new MarkRef(id, sequence, "04600000000015", envelope);
    }

    /** Counts what is revealed and under which purpose, around the real envelope implementation. */
    private static final class CountingProtection implements FieldProtection {

        final FieldProtection delegate;
        final AtomicInteger reveals = new AtomicInteger();
        final Set<String> purposes = java.util.concurrent.ConcurrentHashMap.newKeySet();

        CountingProtection(FieldProtection delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
            return delegate.protect(tenantId, dataClass, record, plaintext);
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            reveals.incrementAndGet();
            purposes.add(purpose);
            return delegate.reveal(tenantId, value, record, purpose);
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return delegate.lookupHash(tenantId, lookupDomain, normalizedValue);
        }
    }
}
