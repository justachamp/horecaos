package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup.FiscalClassificationFacts;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderMarksPort.LineMarkCount;
import uz.horecaos.platform.ordering.api.OrderMarksPort.MarkRef;
import uz.horecaos.platform.ordering.api.OrderMarksPort.ReceiptLine;
import uz.horecaos.platform.ordering.api.OrderMarksPort.ReceiptSource;
import uz.horecaos.platform.payments.application.MarkedOrderReceiptProjection;
import uz.horecaos.platform.payments.application.MarkedOrderReceiptProjection.Result;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalDocumentType;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.support.TestProtection;

/**
 * ADR 0161, Decision 6: what the receipt of a marked order is made of -- and what it is never made of.
 *
 * <p>The properties that matter and that a green "it built something" test would miss: a marked order
 * <em>never</em> becomes the synthetic aggregate line; a provider with no field for a code is refused
 * <em>before</em> a single code is decrypted; an incomplete order decrypts nothing; the disclosure is
 * recorded once, as a count and never as a code; and the lines add up to the sale exactly.
 */
class MarkedOrderReceiptProjectionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID VODKA = UUID.randomUUID();
    private static final UUID PLOV = UUID.randomUUID();
    private static final String TIN = "123456789";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T09:00:00Z"), ZoneOffset.UTC);

    private static final String CODE_A = "010460000000001521FiscalSerialAAA1";
    private static final String CODE_B = "010460000000001521FiscalSerialBBB2";
    private static final String CODE_C = "010460000000001521FiscalSerialCCC3";

    private CountingProtection protection;
    private FakeMarks marks;
    private Map<UUID, FiscalClassificationFacts> classifications;
    private PaymentFiscalService receipts;
    private List<AuditFact> audit;
    private MarkedOrderReceiptProjection projection;

    @BeforeEach
    void wire() {
        protection = new CountingProtection(TestProtection.envelope());
        marks = new FakeMarks();
        classifications = new HashMap<>();
        classifications.put(
                VODKA,
                new FiscalClassificationFacts("22030000000000000", "123456", 796, "Vodka 0.5l", "4600000000015", true));
        classifications.put(
                PLOV, new FiscalClassificationFacts("10601001001000000", "654321", 796, "Plov", null, false));
        receipts = mock(PaymentFiscalService.class);
        when(receipts.carriesMarkingCodes(PaymentProviderType.CLICK)).thenReturn(true);
        when(receipts.carriesMarkingCodes(PaymentProviderType.PAYME)).thenReturn(false);
        audit = new ArrayList<>();
        AuditRecorder recorder = audit::add;
        projection = new MarkedOrderReceiptProjection(
                marks, (tenantId, brandId, ids) -> classifications, receipts, protection, recorder, CLOCK);
    }

    @Test
    @DisplayName("an order with no marked line is left to the caller: nothing is built, decrypted or audited")
    void anOrdinaryOrderIsNotProjected() {
        marks.source = new ReceiptSource(BRAND, "UZS", List.of(plov(2)));

        Result result = projection.project(document(PaymentProviderType.CLICK), TIN);

        assertThat(result).isInstanceOf(Result.NotMarked.class);
        assertThat(protection.reveals.get()).isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("a marked line becomes one line of quantity N carrying the N codes in the order they were scanned")
    void aMarkedLineCarriesItsCodesInSequence() {
        // Stored out of order: the sequence, not the storage order, decides.
        marks.source = new ReceiptSource(
                BRAND, "UZS", List.of(vodka(3, List.of(mark(3, CODE_C), mark(1, CODE_A), mark(2, CODE_B)))));

        Result result = projection.project(document(PaymentProviderType.CLICK), TIN);

        FiscalReceiptLine line = lines(result).get(0);
        assertThat(line.quantity()).isEqualTo(3);
        assertThat(line.markingCodes()).containsExactly(CODE_A, CODE_B, CODE_C);
        assertThat(line.mxikCode()).isEqualTo("22030000000000000");
        assertThat(line.packageCode()).isEqualTo("123456");
        assertThat(line.fiscalName()).isEqualTo("Vodka 0.5l");
        assertThat(line.barcode()).isEqualTo("4600000000015");
        assertThat(line.commissionTin()).isEqualTo(TIN);
    }

    @Test
    @DisplayName(
            "the lines add up to the sale to the minor unit, whatever the division, and the tax share is the quote's")
    void theLinesAddUpToTheSale() {
        // 3 units for 100 001: not divisible, so a line discount carries the remainder.
        ReceiptLine odd = new ReceiptLine(
                UUID.randomUUID(),
                VODKA,
                "Vodka",
                BigDecimal.valueOf(3),
                100_001L,
                10_714L,
                true,
                3,
                List.of(mark(1, CODE_A), mark(2, CODE_B), mark(3, CODE_C)));
        marks.source = new ReceiptSource(BRAND, "UZS", List.of(odd, plov(1)));

        Result result = projection.project(document(PaymentProviderType.CLICK), TIN);

        List<FiscalReceiptLine> built = lines(result);
        long total = 0;
        for (FiscalReceiptLine line : built) {
            long discount = line.discount() == null ? 0 : line.discount().value();
            total += line.unitPrice().value() * line.quantity() - discount;
        }
        assertThat(total).as("the receipt total").isEqualTo(100_001L + 25_000L);
        assertThat(built.get(0).taxAmount().value())
                .as("the quote's tax share, not recomputed")
                .isEqualTo(10_714L);
        assertThat(built.get(0).vatPercent())
                .as("derived from the share and the net, a whole percent")
                .isEqualTo(12);
    }

    @Test
    @DisplayName(
            "the other lines of a marked order are built from their own classification, so the receipt covers the sale")
    void unmarkedLinesAreBuiltToo() {
        marks.source = new ReceiptSource(BRAND, "UZS", List.of(vodka(1, List.of(mark(1, CODE_A))), plov(2)));

        List<FiscalReceiptLine> built = lines(projection.project(document(PaymentProviderType.CLICK), TIN));

        assertThat(built).hasSize(2);
        assertThat(built.get(1).fiscalName()).isEqualTo("Plov");
        assertThat(built.get(1).quantity()).isEqualTo(2);
        assertThat(built.get(1).markingCodes()).isEmpty();
        assertThat(built).noneMatch(line -> line.mxikCode().equals("00000000000000000"));
    }

    @Test
    @DisplayName(
            "an unclassified unmarked line takes the visibly synthetic placeholder; an unclassified marked line blocks")
    void unclassifiedLines() {
        classifications.remove(PLOV);
        marks.source = new ReceiptSource(BRAND, "UZS", List.of(vodka(1, List.of(mark(1, CODE_A))), plov(1)));

        List<FiscalReceiptLine> built = lines(projection.project(document(PaymentProviderType.CLICK), TIN));

        assertThat(built.get(1).mxikCode()).isEqualTo("00000000000000000");

        classifications.remove(VODKA);
        Result blocked = projection.project(document(PaymentProviderType.CLICK), TIN);

        assertThat(blocked)
                .isInstanceOfSatisfying(
                        Result.Unprojectable.class,
                        refused -> assertThat(refused.reason()).isEqualTo("MARKED_LINE_UNCLASSIFIED"));
    }

    @Test
    @DisplayName("an incomplete marked line builds nothing and decrypts nothing")
    void incompleteMarksDecryptNothing() {
        marks.source = new ReceiptSource(BRAND, "UZS", List.of(vodka(3, List.of(mark(1, CODE_A), mark(2, CODE_B)))));

        Result result = projection.project(document(PaymentProviderType.CLICK), TIN);

        assertThat(result).isInstanceOf(Result.MarksIncomplete.class);
        assertThat(protection.reveals.get()).isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("a provider with no field for a code is refused before any code is decrypted")
    void aProviderThatCannotCarryCodesDecryptsNothing() {
        marks.source = new ReceiptSource(BRAND, "UZS", List.of(vodka(1, List.of(mark(1, CODE_A)))));

        Result result = projection.project(document(PaymentProviderType.PAYME), TIN);

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Unprojectable.class,
                        refused -> assertThat(refused.reason()).isEqualTo("MARKING_CODES_UNSUPPORTED"));
        assertThat(protection.reveals.get())
                .as("no code was decrypted for a document that cannot be sent")
                .isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName(
            "each code is decrypted once, under the receipt purpose, and one fact records the count and never a code")
    void theDisclosureIsRecordedOnceAndNeverCarriesACode() {
        marks.source =
                new ReceiptSource(BRAND, "UZS", List.of(vodka(2, List.of(mark(1, CODE_A), mark(2, CODE_B))), plov(1)));

        projection.project(document(PaymentProviderType.CLICK), TIN);

        assertThat(protection.reveals.get()).isEqualTo(2);
        assertThat(protection.purposes).containsOnly(MarkedOrderReceiptProjection.PURPOSE);
        assertThat(audit).hasSize(1);
        AuditFact fact = audit.get(0);
        assertThat(fact.actionCode()).isEqualTo("payments.document.marking_codes_disclosed");
        assertThat(fact.targetType()).isEqualTo("fiscal_document");
        assertThat(fact.changeDocument().toString()).contains("codeCount").contains("2");
        assertThat(fact.toString()).doesNotContain("FiscalSerial");
        assertThat(fact.changeDocument().toString()).doesNotContain("FiscalSerial");
        assertThat(fact.reason()).isEqualTo(MarkedOrderReceiptProjection.PURPOSE);
    }

    @Test
    @DisplayName(
            "a ciphertext copied onto another mark's row fails to decrypt instead of putting a stranger's code on the receipt")
    void aCodeBoundToAnotherRowIsNotRevealed() {
        UUID realId = UUID.randomUUID();
        String stolen = protection
                .delegate
                .protect(
                        TENANT,
                        DataClass.FINANCIAL,
                        new RecordRef(OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, UUID.randomUUID()),
                        CODE_A)
                .serialize();
        marks.source = new ReceiptSource(
                BRAND, "UZS", List.of(vodka(1, List.of(new MarkRef(realId, 1, "04600000000015", stolen)))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> projection.project(document(PaymentProviderType.CLICK), TIN))
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
        assertThat(audit)
                .as("nothing is recorded for a disclosure that did not happen")
                .isEmpty();
    }

    @Test
    @DisplayName("vatPercentOf is a whole percent from the tax share and the net, and zero when nothing was taxed")
    void vatPercent() {
        assertThat(MarkedOrderReceiptProjection.vatPercentOf(112_000L, 12_000L)).isEqualTo(12);
        assertThat(MarkedOrderReceiptProjection.vatPercentOf(100_000L, 0L)).isZero();
        assertThat(MarkedOrderReceiptProjection.vatPercentOf(0L, 0L)).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private static List<FiscalReceiptLine> lines(Result result) {
        assertThat(result).isInstanceOf(Result.Lines.class);
        return ((Result.Lines) result).lines();
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

    private ReceiptLine vodka(int units, List<MarkRef> refs) {
        return new ReceiptLine(
                UUID.randomUUID(), VODKA, "Vodka", BigDecimal.valueOf(units), 100_000L * units, 0L, true, units, refs);
    }

    private static ReceiptLine plov(int units) {
        return new ReceiptLine(
                UUID.randomUUID(), PLOV, "Plov", BigDecimal.valueOf(units), 25_000L * units, 0L, false, 0, List.of());
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

    private static final class FakeMarks implements OrderMarksPort {

        ReceiptSource source = new ReceiptSource(BRAND, "UZS", List.of());

        @Override
        public List<LineMarkCount> markCounts(UUID tenantId, UUID orderId) {
            return List.of();
        }

        @Override
        public ReceiptSource receiptSource(UUID tenantId, UUID orderId) {
            return source;
        }
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
