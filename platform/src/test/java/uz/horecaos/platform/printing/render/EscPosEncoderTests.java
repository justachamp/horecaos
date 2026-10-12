package uz.horecaos.platform.printing.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.support.printing.EscPosDecoder;
import uz.horecaos.platform.support.printing.EscPosDecoder.Printed;

/** The framing (ADR 0154, Decision 3): the bitmap in raster bands, and the decoded bytes are the bitmap. */
class EscPosEncoderTests {

    private final DocumentRenderer renderer = new DocumentRenderer();

    @Test
    @DisplayName("the bytes an agent writes decode to exactly the bitmap the console previewed, band by band")
    void theBytesDecodeToThePreviewBitmap() {
        for (int width : new int[] {58, 80}) {
            var rendered = renderer.render(
                    RenderFixtures.receipt("ru", width, false), RenderContext.fresh(RenderFixtures.NOW));

            Printed printed = EscPosDecoder.decode(renderer.escPosBytes(rendered));

            assertThat(printed.bitmap()).isEqualTo(rendered.bitmap());
            assertThat(printed.initialised()).isTrue();
            assertThat(printed.cut()).isTrue();
            assertThat(printed.feedLines()).isEqualTo(EscPosEncoder.FEED_BEFORE_CUT);
            assertThat(printed.bands())
                    .as("128 rows to a band")
                    .isEqualTo((rendered.bitmap().height() + EscPosEncoder.BAND_ROWS - 1) / EscPosEncoder.BAND_ROWS);
            assertThat(printed.bands()).isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("a document with no cut is fed and not cut")
    void noCutNoCut() {
        var rendered = renderer.render(
                RenderFixtures.document(PrintDocumentType.KITCHEN_TICKET, "en", 58, false, Text.plain("x")),
                RenderContext.fresh(RenderFixtures.NOW));

        assertThat(EscPosDecoder.decode(renderer.escPosBytes(rendered)).cut()).isFalse();
    }

    @Test
    @DisplayName("the decoder refuses a command the encoder never writes, so a framing change is a failing test")
    void theDecoderRefusesAnUnknownCommand() {
        assertThatThrownBy(() -> EscPosDecoder.decode(new byte[] {0x1B, 0x40, 0x1B, 0x21, 0x08}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown ESC/POS command");
    }

    @Test
    @DisplayName("the protocol the job records names the framing")
    void theProtocolIsNamed() {
        assertThat(EscPosEncoder.PROTOCOL).isEqualTo("ESCPOS_RASTER_1");
    }
}
