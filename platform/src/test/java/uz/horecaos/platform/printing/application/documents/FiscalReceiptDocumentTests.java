package uz.horecaos.platform.printing.application.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.Evidence;
import uz.horecaos.platform.printing.application.documents.FiscalReceiptDocument.NotPrintableException;
import uz.horecaos.platform.printing.application.documents.FiscalReceiptDocument.SellerMismatchException;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Fiscal;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Value;
import uz.horecaos.platform.printing.render.CellLayout;
import uz.horecaos.platform.printing.render.DocumentRenderer;
import uz.horecaos.platform.printing.render.Layout;
import uz.horecaos.platform.printing.render.PrintLabels;
import uz.horecaos.platform.printing.render.RenderContext;
import uz.horecaos.platform.support.printing.QrReader;
import uz.horecaos.platform.support.printing.Snapshots;

/**
 * The fiscal receipt (ADR 0154, Decisions 2 and 5): one ISSUED SALE document printed as stored, its evidence
 * verbatim in one indivisible block, the QR of the stored URL, and a refusal -- naming the state -- for
 * anything else.
 */
class FiscalReceiptDocumentTests {

    private static PrintDocument receipt(String locale, int width, boolean copy) {
        return FiscalReceiptDocument.build(
                DocumentFixtures.context(locale, width, copy, "Rahmat!", "Kelib turing"),
                DocumentFixtures.receipt("SALE", "ISSUED", DocumentFixtures.evidence()),
                DocumentFixtures.seller());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUBMITTED", "FAILED", "BLOCKED", "NOT_APPLICABLE"})
    @DisplayName("a document that is not ISSUED is refused, naming its state")
    void aDocumentThatIsNotIssuedIsRefused(String status) {
        assertThatThrownBy(() -> FiscalReceiptDocument.build(
                        DocumentFixtures.context("en", 80, false),
                        DocumentFixtures.receipt("SALE", status, DocumentFixtures.evidence()),
                        DocumentFixtures.seller()))
                .isInstanceOf(NotPrintableException.class)
                .hasMessageContaining(status)
                .satisfies(refusal ->
                        assertThat(((NotPrintableException) refusal).state()).isEqualTo(status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUND", "CORRECTION"})
    @DisplayName("a refund or a correction is not printed as a receipt in this version, and says which it is")
    void aRefundIsRefused(String type) {
        assertThatThrownBy(() -> FiscalReceiptDocument.build(
                        DocumentFixtures.context("en", 80, false),
                        DocumentFixtures.receipt(type, "ISSUED", DocumentFixtures.evidence()),
                        DocumentFixtures.seller()))
                .isInstanceOf(NotPrintableException.class)
                .hasMessageContaining(type);
    }

    @Test
    @DisplayName("a seller that is not the company the document was issued under is refused: no facsimile")
    void aSellerThatIsNotTheDocumentsIsRefused() {
        var other = new uz.horecaos.platform.tenancy.api.FiscalSeller(
                UUID.randomUUID(),
                DocumentFixtures.TENANT,
                "LE-2",
                "Boshqa MCHJ",
                "987654321",
                true,
                UUID.randomUUID(),
                true,
                UUID.randomUUID(),
                1,
                java.time.LocalDate.of(2026, 1, 1),
                null);

        assertThatThrownBy(() -> FiscalReceiptDocument.build(
                        DocumentFixtures.context("en", 80, false),
                        DocumentFixtures.receipt("SALE", "ISSUED", DocumentFixtures.evidence()),
                        other))
                .isInstanceOf(SellerMismatchException.class);
    }

    @Test
    @DisplayName("the fiscal block prints the evidence as stored, the seller, and the QR of the stored URL")
    void theFiscalBlockIsTheEvidence() {
        Fiscal block = (Fiscal) receipt("en", 80, false).blocks().stream()
                .filter(candidate -> candidate instanceof Fiscal)
                .findFirst()
                .orElseThrow();

        assertThat(block.rows())
                .extracting(KeyValue::key)
                .containsExactly("Receipt No.", "Fiscal sign", "Terminal", "Registered", "Seller", "TIN");
        assertThat(block.rows().get(0).value()).isEqualTo(new Value.Plain("1234567"));
        assertThat(block.rows().get(1).value()).isEqualTo(new Value.Plain("987654321098"));
        assertThat(block.rows().get(2).value()).isEqualTo(new Value.Plain("EP000000000000"));
        assertThat(block.rows().get(3).value()).isInstanceOf(Value.Moment.class);
        assertThat(block.rows().get(4).value()).isEqualTo(new Value.Plain("Sinov MCHJ"));
        assertThat(block.rows().get(5).value()).isEqualTo(new Value.Plain("123456789"));
        assertThat(block.qr()).isNotNull();
        assertThat(java.util.Objects.requireNonNull(block.qr()).data()).isEqualTo(DocumentFixtures.RECEIPT_URL);
    }

    @Test
    @DisplayName("a field the fiscal operator did not return is not printed, and nothing is made up to fill it")
    void aMissingFieldIsNotInvented() {
        PrintDocument document = FiscalReceiptDocument.build(
                DocumentFixtures.context("en", 80, false),
                DocumentFixtures.receipt("SALE", "ISSUED", new Evidence("1234567", null, null, null, null)),
                DocumentFixtures.seller());

        Fiscal block = (Fiscal) document.blocks().stream()
                .filter(candidate -> candidate instanceof Fiscal)
                .findFirst()
                .orElseThrow();
        assertThat(block.rows()).extracting(KeyValue::key).containsExactly("Receipt No.", "Seller", "TIN");
        assertThat(block.qr()).as("no URL, no QR").isNull();
        assertThat(block.caption()).isNull();
    }

    @Test
    @DisplayName("the block sits below the totals and above the footer, with the status slot above it")
    void theBlockIsBetweenTheTotalsAndTheFooter() {
        List<PrintDocument.Block> blocks = receipt("en", 80, false).blocks();

        int total = indexOf(blocks, block -> block instanceof KeyValue keyValue && "TOTAL".equals(keyValue.key()));
        int slot = indexOf(blocks, block -> block instanceof PrintDocument.StatusSlot);
        int fiscal = indexOf(blocks, block -> block instanceof Fiscal);
        int footer =
                indexOf(blocks, block -> block instanceof PrintDocument.Text text && "Rahmat!".equals(text.text()));
        assertThat(total).isLessThan(slot);
        assertThat(slot).isLessThan(fiscal);
        assertThat(fiscal).isLessThan(footer);
    }

    @Test
    @DisplayName("the bitmap's QR decodes to exactly the stored URL and the evidence renders as stored, on both rolls")
    void theBitmapQrIsTheStoredUrl() {
        for (int width : new int[] {58, 80}) {
            var rendered = new DocumentRenderer()
                    .render(receipt("uz-Latn", width, false), RenderContext.fresh(DocumentFixtures.CONFIRMED_AT));

            assertThat(QrReader.read(rendered.bitmap())).contains(DocumentFixtures.RECEIPT_URL);
            String layout = rendered.layout().dump();
            assertThat(layout).contains("1234567").contains("987654321098").contains("EP000000000000");
            // 07:05:01 UTC is 12:05 in Tashkent: the renderer formats the instant in the branch's zone.
            assertThat(layout).contains("08.10.2026 12:05");
        }
    }

    @Test
    @DisplayName("a copy carries the marker, and the marker is above the fiscal block")
    void aCopyIsMarked() {
        Layout copy =
                new CellLayout().layout(receipt("ru", 80, true), RenderContext.fresh(DocumentFixtures.CONFIRMED_AT));

        List<String> texts = copy.rows().stream()
                .map(row ->
                        row.band() instanceof Layout.TextBand band ? band.text().strip() : "")
                .toList();
        assertThat(texts).contains(PrintLabels.forLocale("ru").get("status.copy"));
        int marker = texts.indexOf(PrintLabels.forLocale("ru").get("status.copy"));
        int firstFiscal = copy.rows().stream()
                .filter(Layout.Row::fiscal)
                .findFirst()
                .map(copy.rows()::indexOf)
                .orElseThrow();
        assertThat(marker).isLessThan(firstFiscal);
    }

    @Test
    @DisplayName("the receipt's layout, in three languages on two rolls, is the snapshot a person has read")
    void theReceiptSnapshots() {
        for (String locale : PrintLabels.LOCALES) {
            for (int width : new int[] {58, 80}) {
                Snapshots.assertMatches(
                        "fiscal-receipt-" + locale + "-" + width,
                        new CellLayout()
                                .layout(
                                        receipt(locale, width, false),
                                        RenderContext.fresh(DocumentFixtures.CONFIRMED_AT))
                                .dump());
            }
        }
    }

    private static int indexOf(
            List<PrintDocument.Block> blocks, java.util.function.Predicate<PrintDocument.Block> match) {
        for (int index = 0; index < blocks.size(); index++) {
            if (match.test(blocks.get(index))) {
                return index;
            }
        }
        throw new AssertionError("no such block");
    }
}
