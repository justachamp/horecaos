package uz.horecaos.platform.printing.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.support.printing.QrReader;

/**
 * The bitmap (ADR 0154, Decision 3). Text is asserted by the ink in its cells and everything that does not
 * depend on the font engine exactly: a QR that a decoder reads back to the stored URL, a rule across the
 * roll, a blank gap.
 */
class RasterRendererTests {

    private final DocumentRenderer renderer = new DocumentRenderer();

    @Test
    @DisplayName("the bitmap is the roll's width and the layout's height, and drawing it twice gives the same dots")
    void theBitmapIsDeterministic() {
        for (int width : new int[] {58, 80}) {
            var first = renderer.render(
                    RenderFixtures.receipt("ru", width, false), RenderContext.fresh(RenderFixtures.NOW));
            var second = renderer.render(
                    RenderFixtures.receipt("ru", width, false), RenderContext.fresh(RenderFixtures.NOW));

            assertThat(first.bitmap().width())
                    .isEqualTo(TapeWidth.require(width).dots());
            assertThat(first.bitmap().height()).isEqualTo(first.layout().heightDots());
            assertThat(first.bitmap()).isEqualTo(second.bitmap());
            assertThat(first.bitmap().inkCount()).isGreaterThan(1_000);
        }
    }

    @Test
    @DisplayName("a character puts ink in its own cell only, and a space leaves the cell paper")
    void inkIsInTheCellOfTheCharacter() {
        PrintDocument document =
                RenderFixtures.document(PrintDocumentType.KITCHEN_TICKET, "en", 58, false, Text.plain("AB C"));
        Bitmap1 bitmap = renderer.render(document, RenderContext.fresh(RenderFixtures.NOW))
                .bitmap();
        int cell = TapeWidth.CELL_WIDTH_DOTS;
        int row = TapeWidth.CELL_HEIGHT_DOTS;

        assertThat(bitmap.inkIn(0, 0, cell, row)).isGreaterThan(20);
        assertThat(bitmap.inkIn(cell, 0, cell, row)).isGreaterThan(20);
        assertThat(bitmap.inkIn(2 * cell, 0, cell, row)).as("the space").isZero();
        assertThat(bitmap.inkIn(3 * cell, 0, cell, row)).isGreaterThan(20);
        assertThat(bitmap.inkIn(4 * cell, 0, bitmap.width() - 4 * cell, row))
                .as("the rest of the roll")
                .isZero();
    }

    @Test
    @DisplayName("bold draws more ink than regular for the same word, and double size draws a taller glyph")
    void boldAndSize() {
        int regular = ink(Text.plain("HELLO"));
        int bold = ink(Text.bold("HELLO"));
        int tall = ink(new Text("HELLO", PrintDocument.Size.DOUBLE, PrintDocument.Align.LEFT, false, 0));

        assertThat(bold).isGreaterThan(regular);
        assertThat(tall).isGreaterThan(regular * 2);
    }

    @Test
    @DisplayName("a rule crosses the whole roll and a feed is paper")
    void aRuleAndAFeed() {
        PrintDocument document = RenderFixtures.document(
                PrintDocumentType.KITCHEN_TICKET,
                "en",
                80,
                false,
                new PrintDocument.Rule(false),
                new PrintDocument.Feed(2));
        Bitmap1 bitmap = renderer.render(document, RenderContext.fresh(RenderFixtures.NOW))
                .bitmap();

        assertThat(bitmap.height()).isEqualTo(TapeWidth.CELL_WIDTH_DOTS + 2 * TapeWidth.CELL_HEIGHT_DOTS);
        assertThat(bitmap.inkIn(0, 0, bitmap.width(), TapeWidth.CELL_WIDTH_DOTS))
                .isEqualTo(2 * bitmap.width());
        assertThat(bitmap.inkIn(0, TapeWidth.CELL_WIDTH_DOTS, bitmap.width(), 2 * TapeWidth.CELL_HEIGHT_DOTS))
                .isZero();
    }

    @Test
    @DisplayName("the QR on the bitmap of a receipt decodes to exactly the stored receipt URL, on both rolls")
    void theQrDecodesToTheStoredUrl() {
        for (int width : new int[] {58, 80}) {
            Bitmap1 bitmap = renderer.render(
                            RenderFixtures.receipt("en", width, false), RenderContext.fresh(RenderFixtures.NOW))
                    .bitmap();

            assertThat(QrReader.read(bitmap)).contains(RenderFixtures.RECEIPT_URL);
        }
    }

    @Test
    @DisplayName("a different URL decodes to a different URL: the decoder reads the bitmap and not a constant")
    void theDecoderReadsTheBitmap() {
        PrintDocument document = RenderFixtures.document(
                PrintDocumentType.FISCAL_RECEIPT, "en", 80, false, new PrintDocument.Qr("https://example.test/other"));

        assertThat(QrReader.read(renderer.render(document, RenderContext.fresh(RenderFixtures.NOW))
                        .bitmap()))
                .contains("https://example.test/other");
    }

    private int ink(Text text) {
        return renderer.render(
                        RenderFixtures.document(PrintDocumentType.KITCHEN_TICKET, "en", 80, false, text),
                        RenderContext.fresh(RenderFixtures.NOW))
                .bitmap()
                .inkCount();
    }
}
