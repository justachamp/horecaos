package uz.horecaos.platform.printing.application.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.printing.application.documents.DocumentFixtures.BAR;
import static uz.horecaos.platform.printing.application.documents.DocumentFixtures.GRILL;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.Size;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.render.CellLayout;
import uz.horecaos.platform.printing.render.RenderContext;
import uz.horecaos.platform.support.printing.Snapshots;

/**
 * The kitchen ticket (ADR 0154, Decision 2): one station's items of one ticket, nothing of another station's,
 * a combo as one header with only this station's components beneath it, the sequence label large, and no
 * customer on it.
 */
class KitchenTicketDocumentTests {

    private PrintDocument grill(String locale, int width, int revision) {
        return KitchenTicketDocument.build(
                DocumentFixtures.context(locale, width, false),
                DocumentFixtures.ticket(revision),
                DocumentFixtures.order(null),
                GRILL,
                null,
                revision);
    }

    private PrintDocument bar() {
        return KitchenTicketDocument.build(
                DocumentFixtures.context("en", 80, false),
                DocumentFixtures.ticket(0),
                DocumentFixtures.order(null),
                BAR,
                "Table 7",
                0);
    }

    @Test
    @DisplayName("a station's ticket carries none of another station's lines")
    void aStationsTicketCarriesNoOtherStationsLines() {
        List<String> grillLines = DocumentTexts.itemLabels(grill("en", 80, 0));
        List<String> barLines = DocumentTexts.itemLabels(bar());

        assertThat(grillLines).contains("Lagman (Large)", "Burger").doesNotContain("Cola (0.5 L)", "Fanta");
        assertThat(barLines).contains("Cola (0.5 L)", "Fanta").doesNotContain("Lagman (Large)", "Burger");
    }

    @Test
    @DisplayName("a combo prints one header and only this station's components beneath it")
    void aComboPrintsOneHeaderAndOnlyThisStationsComponents() {
        for (PrintDocument ticket : List.of(grill("en", 80, 0), bar())) {
            List<Item> headers = ticket.blocks().stream()
                    .filter(block -> block instanceof Item item && "Combo No.1".equals(item.label()))
                    .map(block -> (Item) block)
                    .toList();
            assertThat(headers).as("one header, once").hasSize(1);
            assertThat(headers.getFirst().children()).hasSize(1);
        }
        Item grillHeader = (Item) grill("en", 80, 0).blocks().stream()
                .filter(block -> block instanceof Item item && "Combo No.1".equals(item.label()))
                .findFirst()
                .orElseThrow();
        assertThat(grillHeader.children().getFirst().label()).isEqualTo("Burger");
        assertThat(grillHeader.bold()).isTrue();
    }

    @Test
    @DisplayName("the modifiers, the coded preset in the document's language and the guest's note are on the line")
    void theLineCarriesWhatTheCookNeeds() {
        List<String> texts = DocumentTexts.itemLabels(grill("ru", 80, 0));

        assertThat(texts).contains("+ Extra beef", "* Без лука", "! no cilantro please, the guest is allergic");
        assertThat(DocumentTexts.itemLabels(grill("uz-Latn", 80, 0))).contains("* Piyozsiz");
    }

    @Test
    @DisplayName(
            "the sequence label is the largest thing on the ticket, and the table is named where there is a session")
    void theLabelIsLargeAndTheTableIsNamed() {
        PrintDocument ticket = bar();

        Text first = (Text) ticket.blocks().getFirst();
        assertThat(first.text()).isEqualTo("A-014");
        assertThat(first.size()).isEqualTo(Size.TRIPLE);
        assertThat(DocumentTexts.of(ticket)).contains("Table", "Table 7");
        assertThat(DocumentTexts.of(grill("en", 80, 0))).doesNotContain("Table");
    }

    @Test
    @DisplayName("an amendment ticket says so; the ticket as released does not")
    void anAmendmentTicketSaysSo() {
        assertThat(DocumentTexts.of(grill("en", 80, 2))).contains("ORDER AMENDED №2");
        assertThat(DocumentTexts.of(grill("en", 80, 0))).noneMatch(text -> text.contains("AMENDED"));
    }

    @Test
    @DisplayName("nothing on the ticket is a customer: the order the fixture builds it from has none to leak")
    void noCustomerOnTheTicket() {
        // The ports that feed a ticket carry no name, phone or address; this is the document-level half of the
        // record's test (the stored-document half, with an order seeded with all three, is in
        // PrintJobCreationTests).
        String everything = String.join("\n", DocumentTexts.of(grill("ru", 80, 0)));

        assertThat(everything)
                .doesNotContainIgnoringCase("aziz")
                .doesNotContain("+998 90")
                .doesNotContain("Mustaqillik");
    }

    @Test
    @DisplayName("the ticket's layout, in two languages on two rolls, is the snapshot a person has read")
    void theTicketSnapshots() {
        Snapshots.assertMatches("ticket-grill-ru-80", layout(grill("ru", 80, 0)));
        Snapshots.assertMatches("ticket-grill-en-58", layout(grill("en", 58, 0)));
        Snapshots.assertMatches("ticket-grill-uz-80-amended", layout(grill("uz-Latn", 80, 1)));
        Snapshots.assertMatches("ticket-bar-en-80", layout(bar()));
    }

    private static String layout(PrintDocument document) {
        return new CellLayout()
                .layout(document, RenderContext.fresh(DocumentFixtures.CONFIRMED_AT))
                .dump();
    }
}
