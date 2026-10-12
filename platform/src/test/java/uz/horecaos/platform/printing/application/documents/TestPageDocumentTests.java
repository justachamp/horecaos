package uz.horecaos.platform.printing.application.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.render.DocumentRenderer;
import uz.horecaos.platform.printing.render.GlyphFace;
import uz.horecaos.platform.printing.render.PrintLabels;
import uz.horecaos.platform.printing.render.RenderContext;
import uz.horecaos.platform.support.printing.QrReader;

/** The test page (ADR 0154, Decision 2): the roll's width, a ruler, every glyph, a QR, the printer's name. */
class TestPageDocumentTests {

    @Test
    @DisplayName("it names the printer, the roll and the agent's build, and its ruler is one digit per column")
    void itNamesWhatItTests() {
        for (int width : new int[] {58, 80}) {
            PrintDocument page = TestPageDocument.build(
                    DocumentFixtures.context("en", width, false), "Grill pass printer", "agent 1.4.2");
            int columns = width == 58 ? 32 : 48;

            assertThat(DocumentTexts.of(page))
                    .contains("Grill pass printer", "agent 1.4.2", width + " mm / " + columns + " columns")
                    .contains(TestPageDocument.ruler(columns));
            assertThat(TestPageDocument.ruler(columns)).hasSize(columns);
        }
    }

    @Test
    @DisplayName("every glyph line it prints is drawable, and every language's title is")
    void everyGlyphLineIsDrawable() {
        GlyphFace face = GlyphFace.instance();
        for (String line : TestPageDocument.GLYPH_LINES) {
            assertThat(line.codePoints().filter(codePoint -> !face.has(codePoint)))
                    .as(line)
                    .isEmpty();
        }
        for (String locale : PrintLabels.LOCALES) {
            PrintDocument page = TestPageDocument.build(DocumentFixtures.context(locale, 58, false), "P", null);
            DocumentTexts.of(page)
                    .forEach(text -> assertThat(text.codePoints().filter(codePoint -> !face.has(codePoint)))
                            .as(locale + ": " + text)
                            .isEmpty());
        }
    }

    @Test
    @DisplayName("it renders on both rolls and its QR decodes")
    void itRendersAndItsQrDecodes() {
        for (int width : new int[] {58, 80}) {
            var rendered = new DocumentRenderer()
                    .render(
                            TestPageDocument.build(DocumentFixtures.context("ru", width, false), "Принтер", null),
                            RenderContext.fresh(DocumentFixtures.CONFIRMED_AT));

            assertThat(QrReader.read(rendered.bitmap())).contains(TestPageDocument.QR_TEXT);
            assertThat(rendered.bitmap().width()).isEqualTo(width == 58 ? 384 : 576);
        }
    }
}
