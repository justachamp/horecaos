package uz.horecaos.platform.payments.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.SentReceipt;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;

/**
 * Writes the sent lines and the sent tender split behind ADR 0029 protected references
 * (ADR 0166).
 *
 * <p>The class is {@code FINANCIAL}: a receipt line is a price, a classifier and a quantity of
 * something a named customer bought, and the full set of them is what lets an inspector reproduce
 * the sale. They are returned only by the evidence read, which is capability-gated.
 *
 * <p><strong>A marking code never gets this far.</strong> {@link FiscalReceiptLine#markingCodes()} is
 * counted and the count is stored; the codes are not. ADR 0161 keeps every code off every screen
 * and gives it no reveal, so a stored copy here would be a second place to leak one from.
 */
@Component
public class ProtectedSentEvidenceRecorder implements SentEvidenceRecorder {

    private final FieldProtection protection;
    private final JdbcFiscalDocumentStore documents;
    private final ObjectMapper json;

    public ProtectedSentEvidenceRecorder(
            FieldProtection protection, JdbcFiscalDocumentStore documents, ObjectMapper json) {
        this.protection = protection;
        this.documents = documents;
        this.json = json;
    }

    /** A receipt line in the neutral evidence shape: no marking code, only how many were sent. */
    public static SentReceiptEvidence.Line evidenceLineOf(FiscalReceiptLine line) {
        return new SentReceiptEvidence.Line(
                kindOf(line),
                line.fiscalName(),
                line.mxikCode(),
                line.packageCode(),
                line.unitCode(),
                line.quantity(),
                line.unitPrice().value(),
                line.taxAmount().value(),
                line.vatPercent(),
                line.discount() == null ? null : line.discount().value(),
                line.barcode(),
                line.markingCodes().size(),
                line.commissionTin());
    }

    private static String kindOf(FiscalReceiptLine line) {
        return line.kind() == FiscalReceiptLine.Kind.DELIVERY_FEE
                ? SentReceiptEvidence.KIND_DELIVERY_FEE
                : SentReceiptEvidence.KIND_ITEM;
    }

    @Override
    public void record(FiscalDocument document, ProviderBinding binding, SentReceipt sent, Instant sentAt) {
        keep(document, binding.bindingId(), sent, sentAt);
    }

    @Override
    public void recordTerminal(FiscalDocument document, SentReceipt sent, Instant sentAt) {
        keep(document, null, sent, sentAt);
    }

    private void keep(FiscalDocument document, @Nullable UUID merchantBindingId, SentReceipt sent, Instant sentAt) {
        String currency = sent.receivedCard().currency();

        List<SentReceiptEvidence.Line> lines = new ArrayList<>(sent.lines().size());
        for (FiscalReceiptLine line : sent.lines()) {
            lines.add(evidenceLineOf(line));
        }
        String linesJson = json.writeValueAsString(
                new SentReceiptEvidence.Lines(SentReceiptEvidence.VERSION, currency, false, lines));
        String splitJson = json.writeValueAsString(new SentReceiptEvidence.TenderSplit(
                SentReceiptEvidence.VERSION,
                currency,
                sent.receivedCard().value(),
                sent.receivedCash().value(),
                sent.receivedEcash().value()));

        String requestReference = protection
                .protect(
                        document.tenantId(),
                        DataClass.FINANCIAL,
                        SentReceiptEvidence.requestRecord(document.id()),
                        linesJson)
                .serialize();
        String splitReference = protection
                .protect(
                        document.tenantId(),
                        DataClass.FINANCIAL,
                        SentReceiptEvidence.splitRecord(document.id()),
                        splitJson)
                .serialize();

        documents.recordSentEvidence(
                document.tenantId(), document.id(), merchantBindingId, requestReference, splitReference, sentAt);
    }
}
