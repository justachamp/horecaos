package uz.horecaos.platform.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;

/** ADR 0088: the export an accounting system imports, and the spreadsheet it is opened in. */
class CommercialStatementCsvTests {

    @Test
    void aNameThatLooksLikeAFormulaOpensAsText() {
        assertThat(CommercialStatementController.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(CommercialStatementController.cell("-1")).isEqualTo("\"'-1\"");
        assertThat(CommercialStatementController.cell("Kitchen display")).isEqualTo("\"Kitchen display\"");
    }

    @Test
    void everyChargeIsOneRowCarryingItsStatement() {
        Statement statement = new Statement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "S-2026-08-000001",
                "2026-08",
                Instant.parse("2026-07-31T19:00:00Z"),
                Instant.parse("2026-08-31T19:00:00Z"),
                "UZS",
                1_500_000,
                null,
                Statement.ISSUED,
                "finance",
                Instant.parse("2026-09-01T05:00:00Z"),
                "August close",
                null,
                null,
                null,
                List.of(
                        StatementLine.of(1, StatementLine.PLAN, "BASIC@v1", "BASIC v1, MONTHLY", 1, 1_200_000),
                        StatementLine.of(2, StatementLine.MODULE, "kds", "Kitchen display, PER_LOCATION", 3, 100_000)));

        String[] rows = CommercialStatementController.csv(statement).split("\r\n");

        assertThat(rows).hasSize(3);
        assertThat(rows[0]).startsWith("number,period,status,line,kind");
        assertThat(rows[2])
                .isEqualTo("\"S-2026-08-000001\",\"2026-08\",\"ISSUED\",2,\"MODULE\",\"kds\","
                        + "\"Kitchen display, PER_LOCATION\",3,100000,300000,\"UZS\"");
    }
}
