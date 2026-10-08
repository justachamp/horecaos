package uz.horecaos.platform.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.domain.PrepaymentInvoice;

/**
 * The invoice's CSV is opened in a spreadsheet by an accountant. Every cell is quoted, and a value that
 * begins with a formula character is neutralised, because a beneficiary name HorecaOS finance typed is
 * what ends up on a tenant's screen and in its books (the same rule {@code CommercialStatementCsvTests}
 * holds for a statement).
 */
class PrepaymentInvoiceCsvTests {

    private static final Instant ISSUED = Instant.parse("2026-10-08T09:00:00Z");

    private static PrepaymentInvoice invoice(String beneficiary) {
        return new PrepaymentInvoice(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PI-202610-000001",
                1_000_000,
                "UZS",
                ISSUED.plusSeconds(14 * 86_400L),
                beneficiary,
                "Kapitalbank",
                "20208000900123456001",
                "01158",
                "309876543",
                "u",
                ISSUED,
                null,
                null,
                null,
                250_000);
    }

    @Test
    void oneHeaderRowAndOneValueRowWithEveryAmountInMinorUnits() {
        String csv = CommercialOperationsWalletController.csv(invoice("HorecaOS MCHJ"), ISSUED);

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("number,status,currency,amount_minor,paid_minor,due_minor");
        assertThat(lines[1])
                .contains("\"PI-202610-000001\"")
                .contains("\"PARTIALLY_PAID\"")
                .contains(",1000000,250000,750000,");
    }

    @Test
    void aFormulaCharacterAtTheStartOfAnyTextCellIsNeutralisedAndQuotesAreDoubled() {
        String csv = CommercialOperationsWalletController.csv(invoice("=HYPERLINK(\"http://evil\")"), ISSUED);

        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"").doesNotContain(",\"=HYPERLINK");
        assertThat(CommercialOperationsWalletController.csv(invoice("+1"), ISSUED))
                .contains("\"'+1\"");
        assertThat(CommercialOperationsWalletController.csv(invoice("@SUM(A1)"), ISSUED))
                .contains("\"'@SUM(A1)\"");
    }
}
