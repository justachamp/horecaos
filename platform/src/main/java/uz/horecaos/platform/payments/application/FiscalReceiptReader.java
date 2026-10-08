package uz.horecaos.platform.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.api.FiscalReceiptSource;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * {@link FiscalReceiptSource} over the fiscal document and the intent it settles (ADR 0154).
 *
 * <p>Reads, and only reads. The totals are summed here, in the module that owns the figures, from the
 * lines as they stand on the document -- so the printing module never adds a number of its own to a fiscal
 * receipt. When the document carries no lines (today, always: see the port's note), the one line stated is
 * the line {@link PartnerFiscalizationBridge} submitted, named the same way, so the paper matches the
 * fiscal operator's record.
 */
@Component
class FiscalReceiptReader implements FiscalReceiptSource {

    private final PaymentFiscalService fiscal;
    private final JdbcFiscalDocumentStore documents;
    private final JdbcPaymentIntentStore intents;
    private final OrderDirectory orders;

    FiscalReceiptReader(
            PaymentFiscalService fiscal,
            JdbcFiscalDocumentStore documents,
            JdbcPaymentIntentStore intents,
            OrderDirectory orders) {
        this.fiscal = fiscal;
        this.documents = documents;
        this.intents = intents;
        this.orders = orders;
    }

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
        if (!document.lines().isEmpty()) {
            lines = document.lines().stream().map(FiscalReceiptReader::line).toList();
            currency = document.lines().getFirst().unitPrice().currency();
        } else {
            // The line the partner bridge submits when the document has none: one line for the whole
            // total, named after the order.
            lines = intent.map(found -> {
                        String name = orders.summary(tenantId, document.orderId())
                                .map(order -> "Order " + order.publicOrderNumber())
                                .orElse("Order " + document.orderId());
                        long amount = found.amount().value();
                        return List.of(new Line(name, 1, amount, amount, 0, 0));
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

    private static Line line(FiscalReceiptLine line) {
        long total = line.lineTotal().value()
                - (line.discount() == null ? 0 : line.discount().value());
        return new Line(
                line.fiscalName(),
                line.quantity(),
                line.unitPrice().value(),
                total,
                line.taxAmount().value(),
                line.vatPercent());
    }
}
