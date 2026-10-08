package uz.horecaos.platform.printing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What the platform prints, as structure and not as pixels (ADR 0154, Decision 3).
 *
 * <p>A document is a list of blocks -- text, key and value, a line item with children, a rule, a QR, an
 * indivisible fiscal block, a feed and a cut -- laid out on a fixed character-cell grid and drawn to a
 * 1-bit bitmap by {@code CellLayout} and {@code RasterRenderer}. The same bitmap is the console's
 * preview, the image a browser prints, and the raster band of the ESC/POS bytes an agent receives.
 *
 * <p><strong>What a document holds, and what it does not.</strong> Money is {@code {amountMinor,
 * currency}} and every instant is UTC text; the renderer formats both, the instant in the location's own
 * IANA zone ({@link #zone}). The wording is already in the document's language (one language per
 * document, ADR 0154 Open inputs). The retry, delayed and copy lines are <em>render inputs</em>: the
 * document carries a {@link StatusSlot} where they are drawn and nothing else about them, so the stored
 * document and its digest are the same whether the job was printed first time, retried or delayed.
 *
 * <p>No customer's name, phone or address is ever a field of anything in here. A line's free-text note
 * can hold anything a customer typed, which is why the stored document is envelope-encrypted anyway
 * (ADR 0029).
 *
 * @param schemaVersion the version of this JSON shape; a reader refuses one it does not know
 * @param locale        the document's one language: {@code ru}, {@code uz-Latn} or {@code en}
 * @param tapeWidthMm   58 or 80
 * @param zone          the location's IANA time zone, for the renderer's instants
 * @param copyMarker    whether this print is a copy of one the customer may already hold: every print
 *                      after the first of one document carries the marker (ADR 0154, Decision 5)
 */
public record PrintDocument(
        int schemaVersion,
        PrintDocumentType type,
        String locale,
        int tapeWidthMm,
        String zone,
        boolean copyMarker,
        List<Block> blocks) {

    /** The only version this build writes and reads. */
    public static final int SCHEMA_VERSION = 1;

    public PrintDocument {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unknown print document version " + schemaVersion);
        }
        Objects.requireNonNull(type, "A document type is required");
        Objects.requireNonNull(locale, "A document language is required");
        Objects.requireNonNull(zone, "A document time zone is required");
        TapeWidth.require(tapeWidthMm);
        blocks = List.copyOf(blocks);
    }

    public static PrintDocument of(
            PrintDocumentType type,
            String locale,
            int tapeWidthMm,
            String zone,
            boolean copyMarker,
            List<Block> blocks) {
        return new PrintDocument(SCHEMA_VERSION, type, locale, tapeWidthMm, zone, copyMarker, blocks);
    }

    public TapeWidth tape() {
        return TapeWidth.require(tapeWidthMm);
    }

    /** A copy of this document with the copy marker set, which is all a second print of it differs by. */
    public PrintDocument asCopy() {
        return new PrintDocument(schemaVersion, type, locale, tapeWidthMm, zone, true, blocks);
    }

    // ----------------------------------------------------------------- values

    /** What sits on the right of a key: a word, an amount, or a moment. The renderer formats the last two. */
    public sealed interface Value permits Value.Plain, Value.Amount, Value.Moment {

        record Plain(String text) implements Value {
            public Plain {
                Objects.requireNonNull(text, "Text is required");
            }
        }

        /**
         * Money exactly as the owning module stored it: integer minor units and an ISO currency code.
         *
         * @param withUnit write the currency unit after the number: on a total, not on a row
         */
        record Amount(long amountMinor, String currency, boolean withUnit) implements Value {
            public Amount {
                Objects.requireNonNull(currency, "A currency is required");
            }

            public static Amount row(long amountMinor, String currency) {
                return new Amount(amountMinor, currency, false);
            }

            public static Amount total(long amountMinor, String currency) {
                return new Amount(amountMinor, currency, true);
            }
        }

        /** An instant (ISO-8601, UTC) and how to write it in the location's zone. */
        record Moment(String at, Format format) implements Value {
            public Moment {
                Objects.requireNonNull(at, "An instant is required");
                Objects.requireNonNull(format, "A format is required");
                Instant.parse(at);
            }

            public static Moment of(Instant at, Format format) {
                return new Moment(at.toString(), format);
            }

            public Instant instant() {
                return Instant.parse(at);
            }

            public enum Format {
                DATE_TIME,
                TIME,
                DATE
            }
        }
    }

    // ----------------------------------------------------------------- blocks

    public sealed interface Block permits Text, KeyValue, Item, Rule, Qr, Fiscal, Logo, Feed, Cut, StatusSlot {}

    /** How big a run of text is drawn: the multiple of the 12 by 24 dot cell. */
    public enum Size {
        NORMAL(1),
        DOUBLE(2),
        TRIPLE(3);

        private final int scale;

        Size(int scale) {
            this.scale = scale;
        }

        public int scale() {
            return scale;
        }
    }

    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /**
     * A run of text, wrapped on cells and never on pixels.
     *
     * @param indent cells of left margin, for a note beneath a line
     */
    public record Text(String text, Size size, Align align, boolean bold, int indent) implements Block {
        public Text {
            Objects.requireNonNull(text, "Text is required");
            Objects.requireNonNull(size, "A size is required");
            Objects.requireNonNull(align, "An alignment is required");
            if (indent < 0 || indent > 12) {
                throw new IllegalArgumentException("A text indent is between 0 and 12 cells");
            }
        }

        public static Text plain(String text) {
            return new Text(text, Size.NORMAL, Align.LEFT, false, 0);
        }

        public static Text bold(String text) {
            return new Text(text, Size.NORMAL, Align.LEFT, true, 0);
        }

        public static Text centered(String text) {
            return new Text(text, Size.NORMAL, Align.CENTER, false, 0);
        }

        public static Text title(String text, Size size) {
            return new Text(text, size, Align.CENTER, true, 0);
        }

        public static Text note(String text) {
            return new Text(text, Size.NORMAL, Align.LEFT, false, 2);
        }
    }

    /** A key on the left and its value on the right, on one line when it fits and on two when it does not. */
    public record KeyValue(String key, Value value, boolean bold) implements Block {
        public KeyValue {
            Objects.requireNonNull(key, "A key is required");
            Objects.requireNonNull(value, "A value is required");
        }

        public static KeyValue of(String key, String text) {
            return new KeyValue(key, new Value.Plain(text), false);
        }

        public static KeyValue amount(String key, long amountMinor, String currency, boolean bold) {
            return new KeyValue(key, Value.Amount.total(amountMinor, currency), bold);
        }
    }

    /**
     * One line of an order or a receipt: a label with its quantity, an amount on the right of the first
     * line, and children indented beneath. A combo is a header line with its components as children
     * (ADR 0136).
     *
     * @param quantity a decimal already written ("2", "0.5"), or null for a line that has none
     * @param amount   null for a line with no figure of its own (a kitchen ticket's, or a combo header)
     */
    public record Item(
            String label, @Nullable String quantity, Value.@Nullable Amount amount, boolean bold, List<Item> children)
            implements Block {
        public Item {
            Objects.requireNonNull(label, "A label is required");
            children = List.copyOf(children);
        }

        public static Item of(String label, @Nullable String quantity, Value.@Nullable Amount amount) {
            return new Item(label, quantity, amount, false, List.of());
        }
    }

    /** A horizontal line across the roll. */
    public record Rule(boolean heavy) implements Block {}

    /** A QR code of {@code data}, centred and drawn as filled modules (not through the font). */
    public record Qr(String data) implements Block {
        public Qr {
            Objects.requireNonNull(data, "QR data is required");
            if (data.isBlank()) {
                throw new IllegalArgumentException("A QR needs something to encode");
            }
        }
    }

    /**
     * The fiscal evidence of a receipt and the QR of its URL, as one indivisible block (ADR 0154,
     * Decision 5): below the totals and above the footer, never split across a cut, and never edited by
     * presentation. The renderer draws nothing inside it except what is here.
     */
    public record Fiscal(
            List<KeyValue> rows,
            @Nullable String caption,
            @Nullable Qr qr) implements Block {
        public Fiscal {
            rows = List.copyOf(rows);
        }
    }

    /** The brand's logo, by media asset id; the renderer thresholds it to one bit at the roll's width. */
    public record Logo(String assetId) implements Block {
        public Logo {
            Objects.requireNonNull(assetId, "An asset id is required");
        }
    }

    /** Blank rows. */
    public record Feed(int rows) implements Block {
        public Feed {
            if (rows < 0 || rows > 12) {
                throw new IllegalArgumentException("A feed is between 0 and 12 rows");
            }
        }
    }

    /** Where the roll is cut. The last block of every document. */
    public record Cut() implements Block {}

    /**
     * Where the copy, retry and delayed lines are drawn, when the artifact is rendered. Placed above the
     * fiscal block and never inside it. Holds nothing, so it changes neither the stored document nor its
     * digest.
     */
    public record StatusSlot() implements Block {}
}
