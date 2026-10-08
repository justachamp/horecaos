package uz.horecaos.platform.printing.render;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Align;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Fiscal;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Logo;
import uz.horecaos.platform.printing.domain.PrintDocument.Qr;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocument.Value;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.printing.render.Layout.Band;
import uz.horecaos.platform.printing.render.Layout.CutBand;
import uz.horecaos.platform.printing.render.Layout.GapBand;
import uz.horecaos.platform.printing.render.Layout.ImageBand;
import uz.horecaos.platform.printing.render.Layout.QrBand;
import uz.horecaos.platform.printing.render.Layout.Row;
import uz.horecaos.platform.printing.render.Layout.RuleBand;
import uz.horecaos.platform.printing.render.Layout.TextBand;

/**
 * Places a document's blocks on the fixed character-cell grid (ADR 0154, Decision 3): 12 dots a cell, 32
 * columns on a 58 mm roll's 384 dots and 48 on an 80 mm roll's 576. Wrapping is on cells and never on
 * pixels, so the two rolls differ only in the number of columns.
 *
 * <p>This computes no money and invents no text. Amounts and instants are formatted from what the
 * document holds; every word was written in the document's language when it was built; and the only
 * words added here are the copy, retry and delayed lines that go where the document's {@code
 * StatusSlot} is. Free text (a note a customer typed) has the characters the face cannot draw replaced
 * by {@code ?}; evidence (the fiscal block) refuses to render with one, because a wrong character on a
 * fiscal line is silent.
 */
public final class CellLayout {

    private static final int QUIET_MODULES = 4;
    private static final int LARGEST_MODULE_DOTS = 8;
    /** 0.375 mm on a 203 dpi head: the smallest module a phone reads reliably off thermal paper. */
    private static final int SMALLEST_MODULE_DOTS = 3;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final GlyphFace face;

    public CellLayout() {
        this(GlyphFace.instance());
    }

    CellLayout(GlyphFace face) {
        this.face = face;
    }

    public Layout layout(PrintDocument document, RenderContext context) {
        Run run = new Run(document, context);
        for (Block block : document.blocks()) {
            run.block(block);
        }
        return new Layout(document.tape(), run.rows);
    }

    /** One layout in progress. */
    private final class Run {
        private final PrintDocument document;
        private final RenderContext context;
        private final PrintLabels labels;
        private final ZoneId zone;
        private final TapeWidth tape;
        private final int columns;
        private final List<Row> rows = new ArrayList<>();

        Run(PrintDocument document, RenderContext context) {
            this.document = document;
            this.context = context;
            this.labels = PrintLabels.forLocale(document.locale());
            this.zone = ZoneId.of(document.zone());
            this.tape = document.tape();
            this.columns = tape.columns();
        }

        void block(Block block) {
            switch (block) {
                case Text text -> text(text);
                case KeyValue keyValue -> keyValue(keyValue, false, false);
                case Item item -> item(item, 0);
                case PrintDocument.Rule rule -> add(new RuleBand(rule.heavy()), false);
                case Qr qr -> qr(qr, false);
                case Fiscal fiscal -> fiscal(fiscal);
                case Logo logo -> logo(logo);
                case PrintDocument.Feed feed -> {
                    if (feed.rows() > 0) {
                        add(new GapBand(feed.rows()), false);
                    }
                }
                case PrintDocument.Cut cut -> add(new CutBand(), false);
                case PrintDocument.StatusSlot slot -> statusLines();
            }
        }

        private void add(Band band, boolean fiscal) {
            rows.add(new Row(band, fiscal));
        }

        // ------------------------------------------------------------ text

        private void text(Text block) {
            text(block.text(), block.size().scale(), block.bold(), block.align(), block.indent(), false, false);
        }

        private void text(
                String raw, int scale, boolean bold, Align align, int indent, boolean strict, boolean fiscal) {
            int cells = Math.max(1, columns / scale - indent);
            for (String line : TextCells.wrap(face.sanitize(raw, strict), cells)) {
                int x = indent + TextCells.offset(TextCells.length(line), cells, align);
                add(new TextBand(line, scale, bold, x), fiscal);
            }
        }

        // ------------------------------------------------------- key and value

        private void keyValue(KeyValue block, boolean strict, boolean fiscal) {
            String key = face.sanitize(block.key(), strict);
            String value = face.sanitize(valueText(block.value()), strict);
            if (TextCells.length(key) + 1 + TextCells.length(value) <= columns) {
                int gap = columns - TextCells.length(key) - TextCells.length(value);
                add(new TextBand(key + " ".repeat(gap) + value, 1, block.bold(), 0), fiscal);
                return;
            }
            // Two lines: the key, then the value beneath it flush right so the eye still finds the figure.
            for (String line : TextCells.wrap(key, columns)) {
                add(new TextBand(line, 1, false, 0), fiscal);
            }
            for (String line : TextCells.wrap(value, columns)) {
                add(
                        new TextBand(
                                line, 1, block.bold(), TextCells.offset(TextCells.length(line), columns, Align.RIGHT)),
                        fiscal);
            }
        }

        private String valueText(Value value) {
            return switch (value) {
                case Value.Plain plain -> plain.text();
                case Value.Amount amount -> money(amount);
                case Value.Moment moment -> moment(moment.instant(), moment.format());
            };
        }

        private String money(Value.Amount amount) {
            return MoneyText.format(
                    amount.amountMinor(), amount.currency(), document.locale(), amount.withUnit(), labels);
        }

        private String moment(Instant at, Value.Moment.Format format) {
            ZonedDateTime local = at.atZone(zone);
            return switch (format) {
                case DATE_TIME -> DATE_TIME.format(local);
                case TIME -> TIME.format(local);
                case DATE -> DATE.format(local);
            };
        }

        // ------------------------------------------------------------ items

        private void item(Item item, int indent) {
            String quantity = item.quantity() == null ? "" : item.quantity() + "× ";
            String left = face.sanitize(quantity + item.label(), false);
            String amount = item.amount() == null ? "" : money(item.amount());
            int amountCells = TextCells.length(amount);
            int width = Math.max(4, columns - indent - (amountCells == 0 ? 0 : amountCells + 1));
            List<String> lines = TextCells.wrap(left, width);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (index == 0 && amountCells > 0) {
                    int gap = Math.max(1, columns - indent - TextCells.length(line) - amountCells);
                    line = line + " ".repeat(gap) + amount;
                }
                add(new TextBand(line, 1, item.bold(), indent), false);
            }
            for (Item child : item.children()) {
                item(child, indent + 2);
            }
        }

        // --------------------------------------------------------------- QR

        private void qr(Qr block, boolean fiscal) {
            boolean[][] modules = QrMatrix.encode(block.data());
            int side = modules.length;
            int dots = SMALLEST_MODULE_DOTS - 1;
            for (int candidate = LARGEST_MODULE_DOTS; candidate >= SMALLEST_MODULE_DOTS; candidate--) {
                if ((side + 2 * QUIET_MODULES) * candidate <= tape.dots()) {
                    dots = candidate;
                    break;
                }
            }
            if (dots < SMALLEST_MODULE_DOTS) {
                throw new IllegalArgumentException(
                        "A QR of " + side + " modules does not fit a " + tape.millimetres() + " mm roll");
            }
            add(new QrBand(modules, dots, QUIET_MODULES), fiscal);
        }

        // ----------------------------------------------------------- fiscal

        /**
         * The fiscal block: the stored evidence, a QR of the stored URL and nothing else, as one run of
         * rows marked {@code fiscal}. Evidence is laid out strictly -- a character the face cannot draw
         * fails the render instead of printing a wrong one.
         */
        private void fiscal(Fiscal block) {
            add(new RuleBand(true), true);
            for (KeyValue row : block.rows()) {
                keyValue(row, true, true);
            }
            if (block.caption() != null) {
                text(block.caption(), 1, false, Align.CENTER, 0, true, true);
            }
            if (block.qr() != null) {
                qr(block.qr(), true);
            }
            add(new RuleBand(true), true);
        }

        // ------------------------------------------------------------- logo

        private void logo(Logo block) {
            Optional<Bitmap1> picture = context.logos().apply(block.assetId(), tape.dots());
            picture.ifPresent(image -> add(new ImageBand(image), false));
        }

        // ------------------------------------------------- the status slot

        /**
         * The copy, retry and delayed lines (ADR 0154, Decision 7): each a bold centred line, drawn here
         * and nowhere else, so they are never inside the fiscal block.
         */
        private void statusLines() {
            if (document.copyMarker()) {
                text(labels.get("status.copy"), 1, true, Align.CENTER, 0, false, false);
            }
            if (context.retried()) {
                text(
                        labels.format("status.retry", Integer.toString(context.attempt())),
                        1,
                        true,
                        Align.CENTER,
                        0,
                        false,
                        false);
            }
            if (context.delayed()) {
                ZonedDateTime queued = context.queuedAt().atZone(zone);
                boolean sameDay =
                        queued.toLocalDate().equals(context.now().atZone(zone).toLocalDate());
                String when = (sameDay ? TIME : DAY_TIME).format(queued);
                text(labels.format("status.delayed", when), 1, true, Align.CENTER, 0, false, false);
            }
        }
    }
}
