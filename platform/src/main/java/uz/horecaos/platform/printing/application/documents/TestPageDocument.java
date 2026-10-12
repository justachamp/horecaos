package uz.horecaos.platform.printing.application.documents;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Size;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.printing.render.PrintLabels;

/**
 * The test page (ADR 0154, Decision 2): the roll's width, a ruler, a page of every glyph the three locale
 * catalogues use, a QR, the printer's name and the agent's build.
 *
 * <p>What a person at a new printer needs to see to know that the path works end to end and that the
 * bundled face draws the letters their language needs -- the acceptance test against a real printer the
 * record asks the pilot to run (Open inputs). Computes nothing and reads no order.
 */
public final class TestPageDocument {

    private TestPageDocument() {}

    /** Every character the catalogues print, as lines to read: Latin, the Uzbek Latin letters, Cyrillic, digits. */
    static final List<String> GLYPH_LINES = List.of(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "abcdefghijklmnopqrstuvwxyz",
            "0123456789 .,:;!?()[]/\\|+-=*#@%&'\"",
            "O\u02BB o\u02BB G\u02BB g\u02BB \u02BC \u00D7 \u2116 \u00AB\u00BB \u2014",
            "\u0410\u0411\u0412\u0413\u0414\u0415\u0401\u0416\u0417\u0418\u0419\u041A\u041B\u041C" + "\u041D\u041E",
            "\u041F\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042A\u042B\u042C"
                    + "\u042D\u042E\u042F",
            "\u0430\u0431\u0432\u0433\u0434\u0435\u0451\u0436\u0437\u0438\u0439\u043A\u043B\u043C" + "\u043D\u043E",
            "\u043F\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044A\u044B\u044C"
                    + "\u044D\u044E\u044F",
            "\u040E\u045E \u049A\u049B \u0492\u0493 \u04B2\u04B3");

    /** The QR's content: nothing a person could mistake for a receipt. */
    static final String QR_TEXT = "HORECAOS PRINT TEST";

    public static PrintDocument build(BuildContext context, String printerName, @Nullable String agentBuild) {
        PrintLabels labels = context.labels();
        TapeWidth tape = context.tape();
        List<Block> blocks = new ArrayList<>();
        blocks.add(Text.title(labels.get("title.test_page"), Size.DOUBLE));
        blocks.add(new PrintDocument.StatusSlot());
        blocks.add(KeyValue.of(labels.get("test.printer"), printerName));
        blocks.add(KeyValue.of(
                labels.get("test.roll"),
                tape.millimetres() + " mm / " + tape.columns() + " " + labels.get("test.columns")));
        if (agentBuild != null) {
            blocks.add(KeyValue.of(labels.get("test.agent"), agentBuild));
        }
        blocks.add(new PrintDocument.Rule(false));
        blocks.add(Text.bold(labels.get("test.ruler")));
        blocks.add(Text.plain(ruler(tape.columns())));
        blocks.add(Text.plain(rulerDigits(tape.columns())));
        blocks.add(new PrintDocument.Rule(false));
        blocks.add(Text.bold(labels.get("test.glyphs")));
        for (String line : GLYPH_LINES) {
            blocks.add(Text.plain(line));
        }
        blocks.add(new PrintDocument.Rule(false));
        blocks.add(Text.bold(labels.get("test.qr")));
        blocks.add(new PrintDocument.Qr(QR_TEXT));
        blocks.add(new PrintDocument.Feed(1));
        blocks.add(new PrintDocument.Cut());
        return PrintDocument.of(
                PrintDocumentType.TEST_PAGE,
                context.locale(),
                tape.millimetres(),
                context.zone(),
                context.copy(),
                blocks);
    }

    /** One digit per column, repeating 1..9 0: a ruler a person can count the columns of a roll against. */
    static String ruler(int columns) {
        StringBuilder text = new StringBuilder();
        for (int column = 1; column <= columns; column++) {
            text.append(column % 10);
        }
        return text.toString();
    }

    /** A tick every ten columns, and the tens beneath it. */
    static String rulerDigits(int columns) {
        StringBuilder text = new StringBuilder();
        for (int column = 1; column <= columns; column++) {
            text.append(column % 10 == 0 ? Integer.toString(column / 10 % 10) : ".");
        }
        return text.toString();
    }
}
