package uz.horecaos.platform.printing.application.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.render.CellLayout;
import uz.horecaos.platform.printing.render.PrintLabels;
import uz.horecaos.platform.printing.render.RenderContext;
import uz.horecaos.platform.support.printing.Snapshots;

/**
 * The order slip (ADR 0154, Decisions 2 and 5): never a fiscal document, never headed as a receipt, and the
 * one line that states the fiscal status the only place «чек» / «receipt» appears on it.
 */
class OrderSlipDocumentTests {

    /** The word, per language, that must not appear on a slip outside the fiscal-status line. */
    private static final Pattern RECEIPT_WORD = Pattern.compile("чек|chek|receipt", Pattern.CASE_INSENSITIVE);

    private PrintDocument slip(String locale, String tender, @Nullable String fiscalStatus, @Nullable Long cash) {
        return OrderSlipDocument.build(
                DocumentFixtures.context(locale, 80, false, "Thank you for visiting"),
                DocumentFixtures.order(cash),
                DocumentFixtures.seller(),
                DocumentFixtures.payment(tender, fiscalStatus),
                null);
    }

    private static String statusLine(String locale, String status) {
        return PrintLabels.forLocale(locale).get("slip.fiscal_status." + status);
    }

    @Test
    @DisplayName(
            "in every language the slip says it is not a fiscal document, and only its fiscal-status line says receipt")
    void theSlipIsNeverAReceipt() {
        for (String locale : PrintLabels.LOCALES) {
            for (String status :
                    List.of("NOT_APPLICABLE", "PENDING", "SUBMITTED", "ISSUED", "FAILED", "BLOCKED", "NONE")) {
                PrintDocument slip = slip(locale, "PROVIDER", "NONE".equals(status) ? null : status, null);
                String fiscalStatusLine = statusLine(locale, status);

                List<String> everythingElse = new ArrayList<>(DocumentTexts.of(slip));
                assertThat(everythingElse).contains(fiscalStatusLine);
                everythingElse.remove(fiscalStatusLine);

                assertThat(everythingElse)
                        .as("%s / %s: nothing but the fiscal-status line may say receipt", locale, status)
                        .noneMatch(text -> RECEIPT_WORD.matcher(text).find());
                assertThat(RECEIPT_WORD.matcher(fiscalStatusLine).find())
                        .as("the status line is where the word lives")
                        .isTrue();
                assertThat(DocumentTexts.of(slip))
                        .contains(PrintLabels.forLocale(locale).get("slip.not_fiscal"));
            }
        }
    }

    @Test
    @DisplayName("the header says not a fiscal document and is the first heading under the branch")
    void theHeaderSaysNotAFiscalDocument() {
        PrintDocument slip = slip("en", "CASH", "NOT_APPLICABLE", null);

        List<Text> titles = slip.blocks().stream()
                .filter(block -> block instanceof Text text && text.bold())
                .map(block -> (Text) block)
                .toList();
        assertThat(titles).extracting(Text::text).contains("ORDER", "NOT A FISCAL DOCUMENT");
    }

    @Test
    @DisplayName("a cash order prints what was tendered and the change due as the order states them")
    void cashPrintsTenderedAndChange() {
        PrintDocument cash = slip("en", "CASH", "NOT_APPLICABLE", 150_000L);
        PrintDocument card = slip("en", "PROVIDER", "ISSUED", null);

        assertThat(DocumentTexts.of(cash)).contains("Tendered", "150000 UZS", "Change due", "12000 UZS", "Cash");
        assertThat(DocumentTexts.of(card))
                .doesNotContain("Tendered", "Change due")
                .contains("Online");
    }

    @Test
    @DisplayName("the slip carries no line note, no preset and no logo or footer: nothing a tenant or a customer typed")
    void noNotesNoFooter() {
        String everything = String.join("\n", DocumentTexts.of(slip("en", "PROVIDER", "ISSUED", null)));

        assertThat(everything)
                .doesNotContain("no cilantro")
                .doesNotContain("No onion")
                .doesNotContain("Thank you for visiting");
        assertThat(slip("en", "PROVIDER", "ISSUED", null).blocks())
                .noneMatch(block -> block instanceof PrintDocument.Logo);
    }

    @Test
    @DisplayName("it names the branch, its address and phone, the seller and the order, with the figures as stored")
    void theFactsAreThereAsStored() {
        List<String> texts = DocumentTexts.of(slip("ru", "PROVIDER", "ISSUED", null));

        assertThat(texts)
                .contains("Chilonzor", "Bunyodkor 12, Chilonzor, Tashkent", "+998 71 200 00 00")
                .contains("Sinov MCHJ", "123456789", "A-014")
                .contains("138000 UZS");
    }

    @Test
    @DisplayName("the slip's layout, in three languages, is the snapshot a person has read")
    void theSlipSnapshots() {
        for (String locale : PrintLabels.LOCALES) {
            Snapshots.assertMatches(
                    "slip-" + locale + "-80",
                    new CellLayout()
                            .layout(
                                    slip(locale, "CASH", "NOT_APPLICABLE", 150_000L),
                                    RenderContext.fresh(DocumentFixtures.CONFIRMED_AT))
                            .dump());
        }
    }

    @Test
    @DisplayName("it is built in the document's own language, whatever the others are")
    void oneLanguagePerDocument() {
        PrintDocument russian = slip("ru", "PROVIDER", "ISSUED", null);
        PrintDocument english = slip("en", "PROVIDER", "ISSUED", null);

        assertThat(russian.locale()).isEqualTo("ru");
        assertThat(DocumentTexts.of(russian)).contains("ЗАКАЗ").doesNotContain("ORDER");
        assertThat(DocumentTexts.of(english)).contains("ORDER").doesNotContain("ЗАКАЗ");
        Block last = russian.blocks().getLast();
        assertThat(last).isInstanceOf(PrintDocument.Cut.class);
    }
}
