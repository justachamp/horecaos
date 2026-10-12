package uz.horecaos.platform.printing.application.documents;

import java.util.ArrayList;
import java.util.List;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Fiscal;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Qr;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocument.Value;

/** Every string a document carries, flattened, so a test can ask what words a paper contains. */
final class DocumentTexts {

    private DocumentTexts() {}

    static List<String> of(PrintDocument document) {
        List<String> texts = new ArrayList<>();
        document.blocks().forEach(block -> collect(block, texts));
        return texts;
    }

    /** The labels of items, flattened with their children, in print order. */
    static List<String> itemLabels(PrintDocument document) {
        List<String> labels = new ArrayList<>();
        document.blocks().forEach(block -> {
            if (block instanceof Item item) {
                items(item, labels);
            }
        });
        return labels;
    }

    private static void items(Item item, List<String> into) {
        into.add(item.label());
        item.children().forEach(child -> items(child, into));
    }

    private static void collect(Block block, List<String> into) {
        switch (block) {
            case Text text -> into.add(text.text());
            case KeyValue keyValue -> {
                into.add(keyValue.key());
                into.add(valueText(keyValue.value()));
            }
            case Item item -> {
                into.add(item.label());
                item.children().forEach(child -> collect(child, into));
            }
            case Fiscal fiscal -> {
                fiscal.rows().forEach(row -> collect(row, into));
                if (fiscal.caption() != null) {
                    into.add(fiscal.caption());
                }
                if (fiscal.qr() != null) {
                    into.add(fiscal.qr().data());
                }
            }
            case Qr qr -> into.add(qr.data());
            default -> {}
        }
    }

    private static String valueText(Value value) {
        return switch (value) {
            case Value.Plain plain -> plain.text();
            case Value.Amount amount -> amount.amountMinor() + " " + amount.currency();
            case Value.Moment moment -> moment.at();
        };
    }
}
