package uz.horecaos.platform.printing.render;

import java.time.Instant;
import java.util.List;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Fiscal;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Qr;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocumentType;

/** Documents built by hand for the renderer's own tests: what the builders will produce, small enough to read. */
final class RenderFixtures {

    static final String RECEIPT_URL =
            "https://ofd.soliq.uz/epi?t=EP000000000000&r=1234567&c=20261008120501&s=987654321098";

    static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");

    private RenderFixtures() {}

    static PrintDocument document(PrintDocumentType type, String locale, int width, boolean copy, Block... blocks) {
        return PrintDocument.of(type, locale, width, "Asia/Tashkent", copy, List.of(blocks));
    }

    /** A receipt with a line, a total, a status slot above the fiscal block, and the block. */
    static PrintDocument receipt(String locale, int width, boolean copy) {
        return document(
                PrintDocumentType.FISCAL_RECEIPT,
                locale,
                width,
                copy,
                Text.title("FISCAL RECEIPT", PrintDocument.Size.NORMAL),
                new Item("Lagman", "2", PrintDocument.Value.Amount.row(90_000, "UZS"), false, List.of()),
                new PrintDocument.Rule(false),
                KeyValue.amount("TOTAL", 90_000, "UZS", true),
                new PrintDocument.StatusSlot(),
                new Fiscal(
                        List.of(
                                KeyValue.of("Receipt No.", "1234567"),
                                KeyValue.of("Fiscal sign", "987654321098"),
                                KeyValue.of("Terminal", "EP000000000000"),
                                new KeyValue(
                                        "Registered",
                                        PrintDocument.Value.Moment.of(
                                                Instant.parse("2026-10-08T07:05:01Z"),
                                                PrintDocument.Value.Moment.Format.DATE_TIME),
                                        false),
                                KeyValue.of("Seller", "Sinov MCHJ"),
                                KeyValue.of("TIN", "123456789")),
                        "Verify the receipt by QR",
                        new Qr(RECEIPT_URL)),
                new PrintDocument.Feed(1),
                new PrintDocument.Cut());
    }
}
