package uz.horecaos.platform.payments.notifications;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.api.CustomerAlertPort;
import uz.horecaos.platform.payments.api.FiscalDocumentIssued;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.domain.FiscalDocument;

/**
 * The customer-facing counterpart of {@code
 * notifications.application.FiscalOperationsAlertTrigger}: a document
 * reaching {@code ISSUED} tells the customer whose order it is, with the OFD
 * link, because ADR 0058 calls this "a legal artifact, not a courtesy" — a
 * {@code TRANSACTIONAL_REQUIRED} message the customer's own preference
 * cannot switch off, the same legal basis {@code OrderNotificationTrigger}
 * gives its own confirmation and rejection messages.
 *
 * <p>Lives in {@code payments}, beside {@link PaymentOperationsAlertTrigger}
 * and for the same reason that class's own javadoc gives: {@code payments}
 * already depends on {@code integration}, and {@code integration} already
 * depends on {@code notifications}, so a listener inside {@code notifications}
 * importing {@link FiscalDocumentIssued} from {@code payments.api} would
 * close a cycle through {@code integration}. Calling {@link CustomerAlertPort}
 * from here — the customer-audience twin of {@link
 * uz.horecaos.platform.notifications.api.OperationsAlertPort}, which {@link
 * PaymentOperationsAlertTrigger} already calls from this same package — is
 * the one-way edge that already exists, used for a new reason.
 *
 * <p>{@link FiscalDocumentIssued} deliberately carries no evidence (see its
 * own javadoc); the OFD link is resolved here, at trigger time, through
 * {@link PaymentFiscalService#find}, which is the one call in this codebase
 * already trusted to read it.
 */
@Component
public class FiscalCustomerReceiptTrigger {

    private static final Logger log = LoggerFactory.getLogger(FiscalCustomerReceiptTrigger.class);

    /** The semantic template key a tenant authors this message's wording against. */
    public static final String FISCAL_RECEIPT_ISSUED = "FISCAL_RECEIPT_ISSUED";

    /**
     * {@code "Order"}, matching {@code OrderNotificationTrigger}'s own
     * {@code SUBJECT_TYPE} — deliberately not {@code "FiscalDocument"}.
     * {@code NotificationEligibilityService.evaluate} resolves the recipient
     * and every rendered amount/currency variable from {@code
     * OrderDirectory.summary(tenantId, row.subject_id)} unconditionally, for
     * every class and subject type, so {@code subject_id} has to be the
     * order's own id or delivery fails with "names an order this tenant does
     * not own" the moment the worker picks the row up. The document id lives
     * in the idempotency key instead.
     */
    static final String SUBJECT_TYPE = "Order";

    private final CustomerAlertPort customerAlerts;
    private final PaymentFiscalService fiscal;
    private final Duration expiry;

    public FiscalCustomerReceiptTrigger(
            CustomerAlertPort customerAlerts,
            PaymentFiscalService fiscal,
            // A legal artifact does not go stale the way an alert does; kept
            // generous and separately configurable from
            // FiscalOperationsAlertTrigger's own worklist-oriented default.
            @Value("${horecaos.notifications.fiscal-receipt-expiry:P3D}") Duration expiry) {
        this.customerAlerts = customerAlerts;
        this.fiscal = fiscal;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDocumentIssued(FiscalDocumentIssued event) {
        Optional<FiscalDocument> document = fiscal.find(event.tenantId(), event.documentId());
        if (document.isEmpty()) {
            // Read in the same transaction the event was published from —
            // reachable only if something between publish and this listener
            // rolled the row back, which the BEFORE_COMMIT phase itself
            // should already rule out. Defensive, not expected.
            log.warn("FiscalDocumentIssued for {} but the document is no longer visible", event.documentId());
            return;
        }

        customerAlerts.notifyCustomer(
                event.tenantId(),
                event.orderId(),
                FISCAL_RECEIPT_ISSUED,
                SUBJECT_TYPE,
                event.eventId(),
                // Keyed on the document, not the event id: PaymentFiscalService
                // guards ISSUED against a second write
                // (recordEvidence's own "WHERE status <> 'ISSUED'"), but the
                // same discipline every other trigger in this genre applies —
                // one message per document, however many times its issuance
                // is somehow reconsidered.
                "%s:%s:%s".formatted(FISCAL_RECEIPT_ISSUED, SUBJECT_TYPE, event.documentId()),
                receiptVariables(document.get()),
                event.occurredAt(),
                expiry);
    }

    /**
     * The entire variable set this message ever renders with — the OFD link,
     * and nothing about the order's amount or the seller. Package-visible so
     * a classification test can assert directly that this is the whole set,
     * the same discipline {@code OrderNotificationTrigger#reasonVariables}
     * documents for its own.
     */
    static Map<String, String> receiptVariables(FiscalDocument document) {
        Map<String, String> variables = new LinkedHashMap<>();
        FiscalDocument.@Nullable FiscalEvidence evidence = document.evidence();
        String receiptUrl = evidence == null || evidence.receiptUrl() == null ? "" : evidence.receiptUrl();
        variables.put("receiptUrl", receiptUrl);
        return variables;
    }
}
