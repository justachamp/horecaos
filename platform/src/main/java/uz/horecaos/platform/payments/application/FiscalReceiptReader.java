package uz.horecaos.platform.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.api.FiscalReceiptSource;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * {@link FiscalReceiptSource} over the fiscal document and the intent it settles (ADR 0154).
 *
 * <p>Reads, and only reads. The totals are summed here, in the module that owns the figures, from the
 * lines as they were filed -- so the printing module never adds a number of its own to a fiscal receipt,
 * and the paper matches the fiscal operator's record line for line. The document stores no line rows; what
 * was sent is kept behind an ADR 0029 protected reference (ADR 0166), which is revealed here under a stated
 * purpose. Only when nothing was kept (a request that left and could not be recorded) is the paper reduced to
 * one line for the payment, named after the order, which states no tax because none was kept to state.
 */
@Component
class FiscalReceiptReader implements FiscalReceiptSource {

    private static final Logger log = LoggerFactory.getLogger(FiscalReceiptReader.class);

    private final PaymentFiscalService fiscal;
    private final JdbcFiscalDocumentStore documents;
    private final JdbcPaymentIntentStore intents;
    private final OrderDirectory orders;
    private final FieldProtection protection;
    private final ObjectMapper json;

    FiscalReceiptReader(
            PaymentFiscalService fiscal,
            JdbcFiscalDocumentStore documents,
            JdbcPaymentIntentStore intents,
            OrderDirectory orders,
            FieldProtection protection,
            ObjectMapper json) {
        this.fiscal = fiscal;
        this.documents = documents;
        this.intents = intents;
        this.orders = orders;
        this.protection = protection;
        this.json = json;
    }

    /** The purpose the filed lines are revealed under to draw the paper receipt. */
    static final String PRINT_PURPOSE = "fiscal-receipt-print";

    @Override
    @Transactional(readOnly = true)
    public Optional<FiscalReceipt> find(UUID tenantId, UUID documentId) {
        return fiscal.find(tenantId, documentId).map(document -> receipt(tenantId, document));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FiscalDocumentRef> forOrder(UUID tenantId, UUID orderId) {
        return fiscal.forOrder(tenantId, orderId).stream()
                .map(document -> new FiscalDocumentRef(
                        document.id(),
                        document.documentType().name(),
                        document.status().name(),
                        document.createdAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IssuedDocument> issuedBetween(UUID tenantId, Instant from, Instant to, int limit) {
        return documents.listIssuedBetween(tenantId, from, to, limit).stream()
                .map(row -> new IssuedDocument(row.documentId(), row.orderId(), row.issuedAt()))
                .toList();
    }

    private FiscalReceipt receipt(UUID tenantId, FiscalDocument document) {
        Optional<PaymentIntent> intent = document.paymentIntentId() == null
                ? Optional.empty()
                : intents.find(tenantId, document.paymentIntentId());
        List<Line> lines;
        String currency;
        Optional<SentReceiptEvidence.Lines> filed = filedLines(tenantId, document);
        if (filed.isPresent() && !filed.get().lines().isEmpty()) {
            currency = filed.get().currency();
            lines = filed.get().lines().stream().map(FiscalReceiptReader::line).toList();
        } else {
            // Nothing was kept of what was sent: one line for the payment, named after the order, and no
            // tax, because none was kept to state. The paper says less than the filing, never more.
            lines = intent.map(found -> {
                        String name = orders.summary(tenantId, document.orderId())
                                .map(order -> "Order " + order.publicOrderNumber())
                                .orElse("Order " + document.orderId());
                        long amount = found.amount().value();
                        return List.of(new Line(name, BigDecimal.ONE, amount, amount, 0, 0));
                    })
                    .orElse(List.of());
            currency = intent.map(found -> found.amount().currency()).orElse("UZS");
        }
        long total = lines.stream().mapToLong(Line::lineTotalMinor).sum();
        long vat = lines.stream().mapToLong(Line::taxMinor).sum();
        Evidence evidence = document.fiscalEvidence()
                .map(found -> new Evidence(
                        found.receiptReference(),
                        found.fiscalSign(),
                        found.terminalId(),
                        found.registeredAt(),
                        found.receiptUrl()))
                .orElse(null);
        return new FiscalReceipt(
                document.id(),
                document.tenantId(),
                document.orderId(),
                document.legalEntityId(),
                document.documentType().name(),
                document.status().name(),
                currency,
                document.createdAt(),
                lines,
                total,
                vat,
                evidence);
    }

    /**
     * The lines the latest request carried, as filed. Empty when nothing was sent, or what was sent cannot
     * be opened or is of a shape this reader does not know; the reader then says less and never guesses.
     */
    private Optional<SentReceiptEvidence.Lines> filedLines(UUID tenantId, FiscalDocument document) {
        Optional<String> reference = documents.protectedRequestReference(tenantId, document.id());
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        try {
            String plain = protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(reference.get()),
                    SentReceiptEvidence.requestRecord(document.id()),
                    PRINT_PURPOSE);
            SentReceiptEvidence.Lines sent = json.readValue(plain, SentReceiptEvidence.Lines.class);
            return sent.version() == SentReceiptEvidence.VERSION ? Optional.of(sent) : Optional.empty();
        } catch (RuntimeException unreadable) {
            // No payload and no message: either could carry the thing that failed to parse (ADR 0029).
            log.warn("The filed lines of fiscal document {} could not be opened for printing", document.id());
            return Optional.empty();
        }
    }

    private static Line line(SentReceiptEvidence.Line line) {
        long gross = java.math.BigDecimal.valueOf(line.unitPriceMinor())
                .multiply(line.quantity())
                .longValueExact();
        long total = gross - (line.discountMinor() == null ? 0 : line.discountMinor());
        return new Line(
                line.name(), line.quantity(), line.unitPriceMinor(), total, line.taxAmountMinor(), line.vatPercent());
    }
}
