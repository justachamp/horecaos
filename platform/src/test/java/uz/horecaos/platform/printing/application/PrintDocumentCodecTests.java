package uz.horecaos.platform.printing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocumentType;

/**
 * The stored form of a document (ADR 0154, Specification): canonical JSON whose SHA-256 is kept after the
 * document itself is purged. The properties that matter are that the same document is always the same bytes,
 * that a different document is never the same digest, and that what is read back is what was written -- block
 * kinds included, since a polymorphic list that loses its subtype prints nothing.
 */
class PrintDocumentCodecTests {

    private final PrintDocumentCodec codec = new PrintDocumentCodec();

    private static PrintDocument slip(boolean copy, List<Block> blocks) {
        return PrintDocument.of(PrintDocumentType.ORDER_SLIP, "ru", 80, "Asia/Tashkent", copy, blocks);
    }

    private static List<Block> everyKindOfBlock() {
        return List.of(
                new PrintDocument.Logo("018fc154-1000-7000-8000-0000000000aa"),
                PrintDocument.Text.title("Oqtepa Lavash", PrintDocument.Size.DOUBLE),
                PrintDocument.KeyValue.of("Order", "A-014"),
                PrintDocument.KeyValue.amount("Total", 150_000, "UZS", true),
                new PrintDocument.KeyValue(
                        "At",
                        new PrintDocument.Value.Moment("2026-10-08T07:05:01Z", PrintDocument.Value.Moment.Format.TIME),
                        false),
                PrintDocument.Item.of("Lagman", "2", new PrintDocument.Value.Amount(180_000, "UZS", false)),
                new PrintDocument.Rule(true),
                new PrintDocument.Qr("https://ofd.soliq.uz/epi?t=EP000000000000"),
                new PrintDocument.Fiscal(
                        List.of(PrintDocument.KeyValue.of("FP", "987654321098")),
                        "Fiscal receipt",
                        new PrintDocument.Qr("https://ofd.soliq.uz/epi?t=EP000000000000")),
                new PrintDocument.StatusSlot(),
                new PrintDocument.Feed(3),
                new PrintDocument.Cut());
    }

    @Test
    @DisplayName("every kind of block survives a round trip, in order and with its subtype")
    void everyBlockKindRoundTrips() {
        PrintDocument document = slip(false, everyKindOfBlock());

        PrintDocument back = codec.fromJson(codec.toJson(document));

        assertThat(back).isEqualTo(document);
        List<Class<?>> kinds =
                back.blocks().stream().<Class<?>>map(Object::getClass).toList();
        assertThat(kinds)
                .isEqualTo(document.blocks().stream()
                        .<Class<?>>map(Object::getClass)
                        .toList());
    }

    @Test
    @DisplayName("the same document is always the same bytes and the same digest")
    void aDocumentIsCanonical() {
        PrintDocument one = slip(false, everyKindOfBlock());
        PrintDocument two = slip(false, everyKindOfBlock());

        assertThat(codec.toJson(one)).isEqualTo(codec.toJson(two));
        assertThat(codec.digest(one)).isEqualTo(codec.digest(two)).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("a different document is a different digest: one character, one block, the copy marker")
    void aDifferentDocumentIsADifferentDigest() {
        PrintDocument base = slip(false, List.of(Text.plain("Lagman")));

        assertThat(codec.digest(slip(false, List.of(Text.plain("Lagmaн"))))).isNotEqualTo(codec.digest(base));
        assertThat(codec.digest(slip(false, List.of(Text.plain("Lagman"), new PrintDocument.Cut()))))
                .isNotEqualTo(codec.digest(base));
        assertThat(codec.digest(base.asCopy())).isNotEqualTo(codec.digest(base));
    }

    @Test
    @DisplayName("a version this build does not know is refused, on write and on read")
    void anUnknownVersionIsRefused() {
        String json = codec.toJson(slip(false, List.of(Text.plain("x"))));
        String future = json.replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        assertThat(future).as("the version really is in the text").isNotEqualTo(json);
        assertThatThrownBy(() -> codec.fromJson(future)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PrintDocument(2, PrintDocumentType.ORDER_SLIP, "ru", 80, "UTC", false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("text that is not a document is refused without echoing it")
    void garbageIsRefused() {
        assertThatThrownBy(() -> codec.fromJson("{\"nonsense\": true}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("nonsense");
    }
}
