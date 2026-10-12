package uz.horecaos.platform.printing.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.render.Layout.TextBand;
import uz.horecaos.platform.support.printing.Snapshots;

/**
 * The grid (ADR 0154, Decision 3): text wrapped on cells and never on pixels, the two rolls differing only
 * in columns, and the retry, delayed and copy lines drawn above the fiscal block and never inside it.
 */
class CellLayoutTests {

    private final CellLayout layout = new CellLayout();

    private static RenderContext context(int attempt, Instant queuedAt, Instant now, int lateMinutes) {
        return new RenderContext(attempt, queuedAt, now, lateMinutes, (asset, width) -> Optional.empty());
    }

    @Test
    @DisplayName("the same text wraps to 32 columns on a 58 mm roll and 48 on an 80 mm roll, and nothing else differs")
    void theTwoRollsDifferOnlyInColumns() {
        String sentence = "Please do not add onions and bring the sauce separately";
        for (int width : List.of(58, 80)) {
            PrintDocument document =
                    RenderFixtures.document(PrintDocumentType.KITCHEN_TICKET, "en", width, false, Text.plain(sentence));
            Layout laid = layout.layout(document, RenderContext.fresh(RenderFixtures.NOW));
            int columns = width == 58 ? 32 : 48;
            assertThat(laid.tape().columns()).isEqualTo(columns);
            assertThat(laid.rows())
                    .extracting(row -> ((TextBand) row.band()).text())
                    .allSatisfy(line -> assertThat(TextCells.length(line)).isLessThanOrEqualTo(columns))
                    .as("every word survives the wrap")
                    .satisfies(lines -> assertThat(String.join(" ", lines)).isEqualTo(sentence));
        }
    }

    @Test
    @DisplayName("a word longer than the whole roll is broken where the line ends, not cut off")
    void aLongWordIsBroken() {
        String reference = "R".repeat(40);
        PrintDocument document =
                RenderFixtures.document(PrintDocumentType.KITCHEN_TICKET, "en", 58, false, Text.plain(reference));
        Layout laid = layout.layout(document, RenderContext.fresh(RenderFixtures.NOW));
        assertThat(laid.rows())
                .extracting(row -> ((TextBand) row.band()).text())
                .containsExactly("R".repeat(32), "R".repeat(8));
    }

    @Test
    @DisplayName("an item keeps its amount at the right of its first line, and a child is indented beneath it")
    void anItemKeepsItsAmountAndIndentsItsChildren() {
        Item child = Item.of("Extra cheese", "1", null);
        Item parent = new Item("Lagman", "2", PrintDocument.Value.Amount.row(90_000, "UZS"), false, List.of(child));
        PrintDocument document = RenderFixtures.document(PrintDocumentType.ORDER_SLIP, "en", 58, false, parent);

        Layout laid = layout.layout(document, RenderContext.fresh(RenderFixtures.NOW));

        List<TextBand> bands =
                laid.rows().stream().map(row -> (TextBand) row.band()).toList();
        assertThat(bands).hasSize(2);
        assertThat(bands.get(0).text()).startsWith("2× Lagman").endsWith("90 000");
        assertThat(TextCells.length(bands.get(0).text())).isEqualTo(32);
        assertThat(bands.get(1).xCells()).isEqualTo(2);
        assertThat(bands.get(1).text()).isEqualTo("1× Extra cheese");
    }

    @Test
    @DisplayName("a key and a long value that do not fit one line take two, the value flush right")
    void aLongValueTakesTwoLines() {
        PrintDocument document = RenderFixtures.document(
                PrintDocumentType.FISCAL_RECEIPT, "en", 58, false, KeyValue.of("Fiscal sign", "9".repeat(30)));

        Layout laid = layout.layout(document, RenderContext.fresh(RenderFixtures.NOW));

        List<TextBand> bands =
                laid.rows().stream().map(row -> (TextBand) row.band()).toList();
        assertThat(bands).hasSize(2);
        assertThat(bands.get(0).text()).isEqualTo("Fiscal sign");
        assertThat(bands.get(1).text()).isEqualTo("9".repeat(30));
        assertThat(bands.get(1).xCells()).isEqualTo(2);
    }

    @Test
    @DisplayName("a receipt's layout, three languages on two rolls, is the snapshot a person has read")
    void theReceiptSnapshots() {
        for (String locale : List.of("ru", "uz-Latn", "en")) {
            for (int width : List.of(58, 80)) {
                Layout laid = layout.layout(
                        RenderFixtures.receipt(locale, width, false), RenderContext.fresh(RenderFixtures.NOW));
                Snapshots.assertMatches("receipt-" + locale + "-" + width, laid.dump());
            }
        }
    }

    @Test
    @DisplayName("the retry, delayed and copy lines are drawn above the fiscal block and never inside it")
    void theStatusLinesAreAboveTheFiscalBlock() {
        Instant queuedAt = RenderFixtures.NOW.minusSeconds(20 * 60);
        RenderContext retriedAndDelayed = context(3, queuedAt, RenderFixtures.NOW, 5);

        Layout laid = layout.layout(RenderFixtures.receipt("en", 80, true), retriedAndDelayed);

        List<String> texts = laid.rows().stream()
                .map(row -> row.band() instanceof TextBand band ? band.text() : "")
                .toList();
        assertThat(texts).contains("COPY", "Printed again, attempt 3", "DELAYED: queued at 14:10");

        int firstFiscal = indexOfFirstFiscalRow(laid);
        int lastFiscal = indexOfLastFiscalRow(laid);
        for (String line : List.of("COPY", "Printed again, attempt 3", "DELAYED: queued at 14:10")) {
            assertThat(texts.indexOf(line))
                    .as("%s sits above the fiscal block", line)
                    .isLessThan(firstFiscal);
        }
        assertThat(laid.rows().subList(firstFiscal, lastFiscal + 1))
                .as("every row between the first and the last fiscal row is fiscal evidence")
                .allSatisfy(row -> assertThat(row.fiscal()).isTrue());
    }

    @Test
    @DisplayName("a print that came first time and did not wait carries neither line; the copy marker is its own line")
    void aFirstPrintCarriesNoStatusLines() {
        RenderContext firstTime = context(1, RenderFixtures.NOW, RenderFixtures.NOW, 5);

        List<String> original = textsOf(layout.layout(RenderFixtures.receipt("en", 80, false), firstTime));
        List<String> copy = textsOf(layout.layout(RenderFixtures.receipt("en", 80, true), firstTime));

        assertThat(original).doesNotContain("COPY").noneMatch(line -> line.startsWith("Printed again"));
        assertThat(original).noneMatch(line -> line.startsWith("DELAYED"));
        assertThat(copy).contains("COPY");
    }

    @Test
    @DisplayName("waiting exactly the late marker is not delayed; one second more is, with the time it was queued")
    void theDelayBoundIsStrict() {
        Instant now = RenderFixtures.NOW;

        List<String> atTheBound = textsOf(
                layout.layout(RenderFixtures.receipt("en", 80, false), context(1, now.minusSeconds(300), now, 5)));
        List<String> pastTheBound = textsOf(
                layout.layout(RenderFixtures.receipt("en", 80, false), context(1, now.minusSeconds(301), now, 5)));

        assertThat(atTheBound).noneMatch(line -> line.startsWith("DELAYED"));
        assertThat(pastTheBound).contains("DELAYED: queued at 14:24");
        assertThat(pastTheBound)
                .as("delayed and not retried: attempt 1")
                .noneMatch(line -> line.startsWith("Printed again"));
    }

    @Test
    @DisplayName("the queue time names the day when it was not today")
    void theQueueTimeNamesAnEarlierDay() {
        Instant now = RenderFixtures.NOW;
        Instant yesterday = now.minusSeconds(26 * 3600);

        List<String> lines =
                textsOf(layout.layout(RenderFixtures.receipt("en", 80, false), context(1, yesterday, now, 5)));

        assertThat(lines).contains("DELAYED: queued at 07.10 12:30");
    }

    @Test
    @DisplayName("free text the face cannot draw prints a question mark; evidence refuses to render")
    void evidenceRefusesAWrongCharacter() {
        String emoji = new String(Character.toChars(0x1F600));
        PrintDocument note = RenderFixtures.document(
                PrintDocumentType.KITCHEN_TICKET, "en", 80, false, Text.note("extra spicy " + emoji));
        assertThat(textsOf(layout.layout(note, RenderContext.fresh(RenderFixtures.NOW))))
                .containsExactly("extra spicy ?");

        PrintDocument evidence = RenderFixtures.document(
                PrintDocumentType.FISCAL_RECEIPT,
                "en",
                80,
                false,
                new PrintDocument.Fiscal(List.of(KeyValue.of("Seller", "Sinov " + emoji)), null, null));
        assertThatThrownBy(() -> layout.layout(evidence, RenderContext.fresh(RenderFixtures.NOW)))
                .isInstanceOf(GlyphFace.UnrenderableTextException.class)
                .hasMessageContaining("1F600");
    }

    @Test
    @DisplayName(
            "a QR that cannot fit the roll at a scale a phone can read fails the render rather than shrinking to noise")
    void aQrThatCannotFitFails() {
        String huge = "https://example.test/" + "x".repeat(1800);
        PrintDocument document =
                RenderFixtures.document(PrintDocumentType.FISCAL_RECEIPT, "en", 58, false, new PrintDocument.Qr(huge));

        assertThatThrownBy(() -> layout.layout(document, RenderContext.fresh(RenderFixtures.NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit");
    }

    private static List<String> textsOf(Layout laid) {
        return laid.rows().stream()
                .filter(row -> row.band() instanceof TextBand)
                .map(row -> ((TextBand) row.band()).text().strip())
                .toList();
    }

    private static int indexOfFirstFiscalRow(Layout laid) {
        for (int index = 0; index < laid.rows().size(); index++) {
            if (laid.rows().get(index).fiscal()) {
                return index;
            }
        }
        throw new AssertionError("no fiscal row");
    }

    private static int indexOfLastFiscalRow(Layout laid) {
        for (int index = laid.rows().size() - 1; index >= 0; index--) {
            if (laid.rows().get(index).fiscal()) {
                return index;
            }
        }
        throw new AssertionError("no fiscal row");
    }
}
