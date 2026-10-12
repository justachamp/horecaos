package uz.horecaos.platform.printing.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.domain.TapeWidth;

/**
 * The glyph test the record names as the proof of the bundled face (ADR 0154, Open inputs and Testing):
 * every string the three locale catalogues put on a document, and U+02BB, renders with no missing-glyph
 * box -- on the paper, not in the font file.
 */
class PrintingGlyphCoverageTests {

    private final GlyphFace face = GlyphFace.instance();

    @Test
    @DisplayName("every character of every string the three catalogues print has a real glyph")
    void everyCatalogueCharacterIsDrawable() {
        List<String> missing = new ArrayList<>();
        for (String locale : PrintLabels.LOCALES) {
            PrintLabels labels = PrintLabels.forLocale(locale);
            for (String key : new TreeSet<>(labels.keys())) {
                labels.get(key)
                        .codePoints()
                        .filter(codePoint -> !face.has(codePoint))
                        .forEach(codePoint -> missing.add(locale + "/" + key + " U+"
                                + Integer.toHexString(codePoint).toUpperCase()));
            }
        }
        assertThat(missing)
                .as("a character the face cannot draw prints as a box on a customer's paper")
                .isEmpty();
    }

    @Test
    @DisplayName("the catalogues have the same keys in every language, so no document prints a bare key")
    void theCataloguesHaveTheSameKeys() {
        TreeSet<String> russian = new TreeSet<>(PrintLabels.forLocale("ru").keys());
        for (String locale : PrintLabels.LOCALES) {
            assertThat(new TreeSet<>(PrintLabels.forLocale(locale).keys()))
                    .as("the %s catalogue", locale)
                    .isEqualTo(russian);
        }
    }

    @Test
    @DisplayName(
            "the Uzbek Latin letters, including U+02BB, the Cyrillic Uzbek letters and the Latin Extended set draw")
    void theAlphabetsDraw() {
        String alphabets = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя"
                + "ЎўҚқҒғҲҳ"
                + "oʻ gʻ ʼ ʻ ʼ"
                + "ĀāĂăĄąĆćČčĎďĐđĒēĚěĞğĪīİıŁłŃńŇňŌōŐőŘřŚśŞşŠšŢţŤťŪūŮůŰűŹźŻżŽž"
                + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                + " .,:;!?()[]{}<>/\\|+-=*#@%&'\"№«»—–·•×‘’";
        assertThat(alphabets.codePoints().filter(codePoint -> !face.has(codePoint)))
                .as("code points without a glyph")
                .isEmpty();
    }

    @Test
    @DisplayName("U+02BB is drawn, with ink where the character sits, and not left as an empty cell or a box")
    void theTurnedCommaHasInk() {
        PrintDocument document = RenderFixtures.document(
                PrintDocumentType.KITCHEN_TICKET, "uz-Latn", 58, false, PrintDocument.Text.plain("oʻgʻx"));
        Bitmap1 bitmap = new DocumentRenderer()
                .render(document, RenderContext.fresh(RenderFixtures.NOW))
                .bitmap();

        int cell = TapeWidth.CELL_WIDTH_DOTS;
        // 'o' at cell 0, U+02BB at cell 1, 'g' at 2, U+02BB at 3, 'x' at 4.
        for (int index : new int[] {1, 3}) {
            assertThat(bitmap.inkIn(index * cell, 0, cell, TapeWidth.CELL_HEIGHT_DOTS))
                    .as("the turned comma at cell %d", index)
                    .isGreaterThan(0);
            assertThat(bitmap.inkIn(index * cell, 10, cell, 14))
                    .as("a turned comma sits high: nothing below the x-height at cell %d", index)
                    .isZero();
        }
        assertThat(bitmap.inkIn(5 * cell, 0, cell, TapeWidth.CELL_HEIGHT_DOTS))
                .as("the cell after the text is paper")
                .isZero();
    }

    @Test
    @DisplayName("a character the face lacks is reported as missing, so the test above can fail")
    void aMissingGlyphIsDetected() {
        assertThat(face.has(0x1F600)).isFalse();
        assertThat(face.has(0x4E2D)).isFalse();
        assertThat(face.has('A')).isTrue();
    }

    @Test
    @DisplayName("without the substitution the face has no U+02BB of its own, which is the gap the renderer covers")
    void theFaceHasNoNativeTurnedComma() {
        assertThat(GlyphFace.SUBSTITUTES).containsKeys(0x02BB, 0x02BC);
        assertThat(face.drawnAs(0x02BB)).isEqualTo(0x2018);
        assertThat(face.font(false, 1).canDisplay(0x02BB)).isFalse();
        assertThat(face.font(false, 1).canDisplay(0x2018)).isTrue();
    }
}
