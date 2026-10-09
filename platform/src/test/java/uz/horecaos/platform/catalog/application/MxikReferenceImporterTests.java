package uz.horecaos.platform.catalog.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.application.MxikMappingProfile.MxikImportFormatException;
import uz.horecaos.platform.catalog.application.MxikReferenceImporter.Normalised;
import uz.horecaos.platform.catalog.application.MxikReferenceImporter.ReferenceRow;

/**
 * The importer's reading of a file (ADR 0173), with no database: the mapping profile, the parse of both formats,
 * and what the normaliser refuses. The fixture is invented ({@code src/test/resources/mxik/README.md}); what is
 * tested is the importer's behaviour against a file of that shape, not the authority's real file.
 */
class MxikReferenceImporterTests {

    private static final String POTATO = "01101001001000000";
    private static final String ONION = "01101002001000000";
    private static final String JUICE = "02101002001000000";

    private final MxikFileParser parser = new MxikFileParser();
    private final MxikReferenceImporter importer = new MxikReferenceImporter();

    @Test
    @DisplayName("the CSV fixture normalises: a tree written parents first, lists split, one borrowed label counted")
    void theCsvFixtureNormalises() throws IOException {
        Normalised normalised = normalise("tasnif-fixture.csv", fixture(), Set.of());

        assertThat(normalised.errors()).isEmpty();
        assertThat(normalised.rows()).hasSize(7);
        assertThat(normalised.labelsBorrowed())
                .as("the juice row has a Russian label and no Uzbek one; it takes the Russian text in both")
                .isEqualTo(1);
        ReferenceRow juice = row(normalised, JUICE);
        assertThat(juice.labelUz()).isEqualTo(juice.labelRu()).isEqualTo("Сок яблочный");
        assertThat(juice.packageCodes()).containsExactly("1010", "1011");

        ReferenceRow potato = row(normalised, POTATO);
        assertThat(potato.packageCodes()).containsExactly("1001", "1002");
        assertThat(potato.unitCodes()).containsExactly(166);
        assertThat(potato.level()).isEqualTo((short) 3);
        assertThat(potato.labelEn()).isEqualTo("Fresh potato");
        assertThat(row(normalised, ONION).labelEn())
                .as("an absent English label stays absent")
                .isNull();

        assertThat(potato.depth()).as("food > vegetables > potato").isEqualTo(2);
        assertThat(row(normalised, "01000000000000000").depth()).isZero();
        // The apply writes by depth, shallowest first, so a parent exists before the child that names it
        // (fk_mxik_reference_parent is not deferrable). A row listed before its parent would still be ordered.
        assertThat(normalised.codeLengths())
                .as("the distribution is shown to the human, not asserted: seven codes, all seventeen long")
                .isEqualTo(java.util.Map.of(17, 7));
    }

    @Test
    @DisplayName("the same content as a workbook normalises to the same rows as the CSV")
    void theWorkbookReadsLikeTheCsv() throws IOException {
        List<String[]> table = new java.util.ArrayList<>();
        for (String line : new String(fixture(), UTF_8).split("\n")) {
            table.add(line.split(",", -1));
        }
        byte[] xlsx = workbook(table);

        Normalised fromCsv = normalise("tasnif-fixture.csv", fixture(), Set.of());
        Normalised fromXlsx = normalise("tasnif-fixture.xlsx", xlsx, Set.of());

        assertThat(fromXlsx.errors()).isEmpty();
        assertThat(fromXlsx.rows())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("ordinal")
                .containsExactlyElementsOf(fromCsv.rows());
        assertThat(parser.parse("x.xlsx", xlsx).profile()).isEqualTo(MxikMappingProfile.TASNIF_XLSX_V1);
        assertThat(parser.parse("x.CSV", fixture()).profile())
                .as("format follows the extension, case-insensitively")
                .isEqualTo(MxikMappingProfile.TASNIF_CSV_V1);
    }

    @Test
    @DisplayName("a column no alias names stops the run and says which: nothing is guessed")
    void anUnknownHeaderStopsTheRun() {
        String file = "code,label ru,label uz,mystery column\n" + POTATO + ",Картофель,Kartoshka,x\n";

        assertThatThrownBy(() -> parser.parse("f.csv", file.getBytes(UTF_8)))
                .isInstanceOfSatisfying(MxikImportFormatException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("UNKNOWN_HEADER");
                    assertThat(refused.getMessage()).contains("mystery column");
                });
    }

    @Test
    @DisplayName("a file with no code column, or no label column at all, is not this profile's file")
    void aFileWithoutTheRequiredColumnsIsRefused() {
        assertThatThrownBy(() -> parser.parse("f.csv", "label ru,label uz\nКартофель,Kartoshka\n".getBytes(UTF_8)))
                .isInstanceOfSatisfying(
                        MxikImportFormatException.class,
                        refused -> assertThat(refused.code()).isEqualTo("REQUIRED_COLUMN_MISSING"));
        assertThatThrownBy(() -> parser.parse("f.csv", ("code,level\n" + POTATO + ",3\n").getBytes(UTF_8)))
                .isInstanceOfSatisfying(
                        MxikImportFormatException.class,
                        refused -> assertThat(refused.code()).isEqualTo("REQUIRED_COLUMN_MISSING"));
    }

    @Test
    @DisplayName("two columns that both read as the code are refused, not merged")
    void aDuplicatedColumnIsRefused() {
        assertThatThrownBy(() -> parser.parse("f.csv", "code,mxik,label ru\nA,B,C\n".getBytes(UTF_8)))
                .isInstanceOfSatisfying(
                        MxikImportFormatException.class,
                        refused -> assertThat(refused.code()).isEqualTo("DUPLICATE_HEADER"));
    }

    @Test
    @DisplayName(
            "blank, duplicated, too-long and self-parented codes are row errors that name the row, never the content")
    void badRowsAreReportedNotLoaded() {
        String tooLong = "9".repeat(33);
        String file = String.join(
                "\n",
                "code,parent code,label ru,label uz,unit codes,level",
                POTATO + ",,Картофель,Kartoshka,166,3",
                ",,Без кода,Kodsiz,,3",
                POTATO + ",,Картофель дубль,Kartoshka dubl,166,3",
                tooLong + ",,Длинный,Uzun,,3",
                "SELF,SELF,Сам себе родитель,O'ziga ota,,3",
                "NOLABEL,,,,,3",
                "BADUNIT,,Единица,Birlik,abc,3",
                "BADLEVEL,,Уровень,Daraja,,deep",
                "");

        Normalised normalised = normalise("f.csv", file.getBytes(UTF_8), Set.of());

        assertThat(normalised.rows()).extracting(ReferenceRow::code).containsExactly(POTATO);
        assertThat(normalised.errors())
                .extracting(error -> error.row() + ":" + error.detailCode())
                .containsExactly(
                        "2:CODE_BLANK",
                        "3:CODE_DUPLICATED",
                        "4:CODE_TOO_LONG",
                        "5:PARENT_IS_SELF",
                        "6:LABEL_MISSING",
                        "7:UNIT_CODE_NOT_A_NUMBER",
                        "8:LEVEL_NOT_A_NUMBER");
        assertThat(normalised.errors().toString())
                .as("an error carries a stable code and the row number, not the row's text")
                .doesNotContain("Картофель")
                .doesNotContain("Kartoshka");
    }

    @Test
    @DisplayName("a parent the file does not contain and the list does not hold drops its row and its children")
    void anOrphanDropsItsDescendants() {
        String file = String.join(
                "\n",
                "code,parent code,label ru,label uz",
                "ROOT,,Корень,Ildiz",
                "KID,GONE,Сирота,Yetim",
                "GRANDKID,KID,Внук сироты,Yetimning nabirasi",
                "FINE,ROOT,Нормальный,Oddiy",
                "");

        Normalised normalised = normalise("f.csv", file.getBytes(UTF_8), Set.of());

        assertThat(normalised.rows()).extracting(ReferenceRow::code).containsExactly("ROOT", "FINE");
        assertThat(normalised.errors())
                .extracting(error -> error.code() + ":" + error.detailCode())
                .containsExactlyInAnyOrder("KID:PARENT_UNKNOWN", "GRANDKID:PARENT_UNKNOWN");
    }

    @Test
    @DisplayName("a delta may name a parent the list already holds")
    void aDeltaMayNameAParentTheListHolds() {
        String file = "code,parent code,label ru,label uz\nNEW,EXISTING,Новый,Yangi\n";

        Normalised alone = normalise("f.csv", file.getBytes(UTF_8), Set.of());
        Normalised withTheList = normalise("f.csv", file.getBytes(UTF_8), Set.of("EXISTING"));

        assertThat(alone.rows()).isEmpty();
        assertThat(withTheList.rows()).extracting(ReferenceRow::code).containsExactly("NEW");
        assertThat(withTheList.rows().get(0).depth())
                .as("depth is within the file: the existing parent is already written")
                .isZero();
    }

    @Test
    @DisplayName("parents that name each other are a cycle and are dropped, not looped over")
    void aCycleIsDropped() {
        String file = "code,parent code,label ru,label uz\nA,B,А,A\nB,A,Б,B\nC,,В,C\n";

        Normalised normalised = normalise("f.csv", file.getBytes(UTF_8), Set.of());

        assertThat(normalised.rows()).extracting(ReferenceRow::code).containsExactly("C");
        assertThat(normalised.errors()).extracting(error -> error.detailCode()).containsOnly("PARENT_CYCLE");
    }

    @Test
    @DisplayName("a code a spreadsheet stored as a number has lost its digits and is refused, not loaded wrong")
    void aNumericCodeIsRefused() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "fixture", "1.0")) {
            Worksheet sheet = workbook.newWorksheet("Sheet1");
            sheet.value(0, 0, "code");
            sheet.value(0, 1, "label ru");
            sheet.value(0, 2, "label uz");
            sheet.value(1, 0, 7.1311100010000000E16);
            sheet.value(1, 1, "Картофель");
            sheet.value(1, 2, "Kartoshka");
        }

        Normalised normalised = normalise("f.xlsx", out.toByteArray(), Set.of());

        assertThat(normalised.rows()).isEmpty();
        assertThat(normalised.errors())
                .extracting(error -> error.detailCode())
                .containsExactly("CODE_STORED_AS_NUMBER");
    }

    @Test
    @DisplayName("a byte-order mark and Windows line endings in a CSV are read, not mistaken for a header")
    void aByteOrderMarkIsTolerated() {
        byte[] body = ("code,label ru,label uz\r\n" + POTATO + ",Картофель,Kartoshka\r\n").getBytes(UTF_8);
        byte[] withMark = new byte[body.length + 3];
        withMark[0] = (byte) 0xEF;
        withMark[1] = (byte) 0xBB;
        withMark[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withMark, 3, body.length);

        Normalised normalised = normalise("f.csv", withMark, Set.of());

        assertThat(normalised.errors()).isEmpty();
        assertThat(normalised.rows()).extracting(ReferenceRow::code).containsExactly(POTATO);
    }

    // ---------------------------------------------------------------------------- helpers

    private Normalised normalise(String fileName, byte[] bytes, Set<String> inTheList) {
        return importer.normalise(parser.parse(fileName, bytes), inTheList::contains);
    }

    private static ReferenceRow row(Normalised normalised, String code) {
        return normalised.rows().stream()
                .filter(row -> row.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + code));
    }

    private static byte[] fixture() throws IOException {
        try (InputStream stream = MxikReferenceImporterTests.class.getResourceAsStream("/mxik/tasnif-fixture.csv")) {
            assertThat(stream).as("the fixture is on the test classpath").isNotNull();
            return stream.readAllBytes();
        }
    }

    private static byte[] workbook(List<String[]> table) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "fixture", "1.0")) {
            Worksheet sheet = workbook.newWorksheet("Sheet1");
            for (int r = 0; r < table.size(); r++) {
                String[] cells = table.get(r);
                for (int c = 0; c < cells.length; c++) {
                    if (!cells[c].isEmpty()) {
                        // Text, never a number: a seventeen-digit code written as a number loses its digits.
                        sheet.value(r, c, cells[c]);
                    }
                }
            }
        }
        return out.toByteArray();
    }
}
