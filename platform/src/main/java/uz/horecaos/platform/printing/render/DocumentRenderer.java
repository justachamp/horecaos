package uz.horecaos.platform.printing.render;

import uz.horecaos.platform.printing.domain.PrintDocument;

/**
 * The renderer, as one object with no framework (ADR 0154, Decision 3): a document and what the render
 * knows about its job in, a layout and a 1-bit bitmap out, and from the bitmap the preview PNG and the
 * ESC/POS bytes. Everything the console previews, a browser prints and an agent receives comes from here.
 */
public final class DocumentRenderer {

    private final CellLayout layout = new CellLayout();
    private final RasterRenderer raster = new RasterRenderer();
    private final EscPosEncoder escPos = new EscPosEncoder();

    /** What a render produced: the layout (deterministic, snapshot-able) and the bitmap drawn from it. */
    public record Rendered(Layout layout, Bitmap1 bitmap) {

        public byte[] png() {
            return bitmap.toPng();
        }
    }

    public Rendered render(PrintDocument document, RenderContext context) {
        Layout laid = layout.layout(document, context);
        return new Rendered(laid, raster.render(laid));
    }

    /** The bytes an agent writes to the printer. */
    public byte[] escPosBytes(Rendered rendered) {
        boolean cut = rendered.layout().rows().stream().anyMatch(row -> row.band() instanceof Layout.CutBand);
        return escPos.encode(rendered.bitmap(), cut);
    }
}
