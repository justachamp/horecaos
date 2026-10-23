package uz.horecaos.platform.printing.application.documents;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.Evidence;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.FiscalReceipt;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.Line;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Fiscal;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Size;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocument.Value;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.render.PrintLabels;
import uz.horecaos.platform.tenancy.api.FiscalSeller;

/**
 * One {@code ISSUED} {@code SALE} fiscal document, printed as stored (ADR 0154, Decisions 2 and 5): the
 * seller's branch, the lines and the totals, the fiscal block and the QR of the stored receipt URL, then the
 * brand's footer.
 *
 * <p><strong>Nothing to compute and nothing to invent.</strong> Every figure is payments' own; the fiscal
 * block prints the receipt reference, the fiscal sign, the terminal reference, the registration time (in
 * the location's zone, by the renderer) and the seller's name and TIN exactly as the fiscal operator
 * returned them, then the QR. A field the operator did not return is not printed, and none is made up. The
 * block sits below the totals and above the footer, the status slot (copy, retry, delayed) above it and
 * never inside.
 *
 * <p>The platform never prints a facsimile: a document that is not {@code ISSUED}, or is not a {@code
 * SALE}, is refused here naming its state, and so is a seller that is not the company the document was
 * issued under.
 */
public final class FiscalReceiptDocument {

    private FiscalReceiptDocument() {}

    /** The document is not a thing a receipt may be printed for. */
    public static final class NotPrintableException extends RuntimeException {
        private final String state;
        private final String type;

        public NotPrintableException(String type, String state) {
            super("A fiscal receipt prints only for an ISSUED SALE document; this one is a " + type + " that is "
                    + state);
            this.type = type;
            this.state = state;
        }

        public String state() {
            return state;
        }

        public String type() {
            return type;
        }
    }

    /** The company on the document is not the company the branch sold as on that date. */
    public static final class SellerMismatchException extends RuntimeException {
        public SellerMismatchException() {
            super("The seller resolved for the branch on the document's date is not the company the document"
                    + " was issued under, so the receipt would not be what the fiscal operator recorded");
        }
    }

    /** Refuses a document a receipt may not be printed for, naming why. Called by every path that builds one. */
    public static void requirePrintable(FiscalReceipt receipt) {
        if (!"SALE".equals(receipt.type()) || !"ISSUED".equals(receipt.status())) {
            throw new NotPrintableException(receipt.type(), receipt.status());
        }
    }

    public static PrintDocument build(BuildContext context, FiscalReceipt receipt, FiscalSeller seller) {
        requirePrintable(receipt);
        if (receipt.legalEntityId() != null && !receipt.legalEntityId().equals(seller.legalEntityId())) {
            throw new SellerMismatchException();
        }
        PrintLabels labels = context.labels();
        String currency = receipt.currency();
        List<Block> blocks = new ArrayList<>();

        if (context.logoAssetId() != null) {
            blocks.add(new PrintDocument.Logo(context.logoAssetId()));
        }
        blocks.add(Text.title(context.branch().name(), Size.NORMAL));
        if (context.branch().address() != null) {
            blocks.add(Text.centered(context.branch().address()));
        }
        if (context.branch().phone() != null) {
            blocks.add(Text.centered(context.branch().phone()));
        }
        blocks.add(Text.title(labels.get("title.fiscal_receipt"), Size.DOUBLE));
        blocks.add(new PrintDocument.Rule(false));
        for (Line line : receipt.lines()) {
            blocks.add(new Item(
                    line.name(),
                    line.quantity().toPlainString(),
                    Value.Amount.row(line.lineTotalMinor(), currency),
                    false,
                    List.of()));
        }
        blocks.add(new PrintDocument.Rule(false));
        if (receipt.vatMinor() > 0) {
            blocks.add(KeyValue.amount(labels.get("receipt.vat"), receipt.vatMinor(), currency, false));
        }
        blocks.add(KeyValue.amount(labels.get("receipt.total"), receipt.totalMinor(), currency, true));
        blocks.add(new PrintDocument.StatusSlot());
        blocks.add(fiscalBlock(labels, receipt.evidence(), seller));
        for (String footer : context.footers()) {
            blocks.add(Text.centered(footer));
        }
        blocks.add(new PrintDocument.Feed(1));
        blocks.add(new PrintDocument.Cut());
        return PrintDocument.of(
                PrintDocumentType.FISCAL_RECEIPT,
                context.locale(),
                context.tape().millimetres(),
                context.zone(),
                context.copy(),
                blocks);
    }

    /** The evidence the fiscal operator returned, verbatim, the seller, and the QR of the stored URL. */
    static Fiscal fiscalBlock(PrintLabels labels, @Nullable Evidence evidence, FiscalSeller seller) {
        List<KeyValue> rows = new ArrayList<>();
        String url = null;
        if (evidence != null) {
            add(rows, labels.get("fiscal.receipt_no"), evidence.receiptReference());
            add(rows, labels.get("fiscal.sign"), evidence.fiscalSign());
            add(rows, labels.get("fiscal.terminal"), evidence.terminalId());
            if (evidence.registeredAt() != null) {
                rows.add(new KeyValue(
                        labels.get("fiscal.registered"),
                        Value.Moment.of(evidence.registeredAt(), Value.Moment.Format.DATE_TIME),
                        false));
            }
            url = evidence.receiptUrl() == null || evidence.receiptUrl().isBlank() ? null : evidence.receiptUrl();
        }
        rows.add(KeyValue.of(labels.get("seller"), seller.legalName()));
        rows.add(KeyValue.of(labels.get("tin"), seller.taxpayerNumber()));
        return new Fiscal(
                rows, url == null ? null : labels.get("fiscal.scan"), url == null ? null : new PrintDocument.Qr(url));
    }

    private static void add(List<KeyValue> rows, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            rows.add(KeyValue.of(key, value));
        }
    }
}
