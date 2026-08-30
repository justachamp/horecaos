package uz.horecaos.platform.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.payments.api.PaymentDirectory;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReason;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentTransactionStore;

/**
 * Read-side answers about an order's payment (ADR 0013).
 *
 * <p>Uncertainty is a field on the answer rather than an exception, because ADR
 * 0031 requires the caller to be able to distinguish requested, provider-pending,
 * completed, failed, and <strong>uncertain</strong>. An uncertain payment reported
 * as a failure is how an operator reissues a charge that already went through.
 */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService implements PaymentDirectory {

    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final JdbcPaymentTransactionStore transactions;
    private final JdbcFiscalDocumentStore documents;

    public PaymentQueryService(
            JdbcPaymentIntentStore intents,
            JdbcPaymentAttemptStore attempts,
            JdbcPaymentTransactionStore transactions,
            JdbcFiscalDocumentStore documents) {
        this.intents = intents;
        this.attempts = attempts;
        this.transactions = transactions;
        this.documents = documents;
    }

    @Override
    public Optional<PaymentSummary> summary(UUID tenantId, UUID orderId) {
        return intents.findLiveForOrder(tenantId, orderId).map(intent -> toSummary(tenantId, intent));
    }

    @Override
    public List<UnfiscalizedCashOrder> unfiscalizedCashOrders(UUID tenantId, Instant from, Instant to, int limit) {
        return documents
                .listNotApplicable(tenantId, FiscalReason.CASH_TENDER_NO_PROVIDER_FISCALIZATION, from, to, limit)
                .stream()
                .map(document -> new UnfiscalizedCashOrder(
                        document.orderId(),
                        document.id(),
                        document.reasonCode(),
                        document.reasonNote(),
                        document.createdAt()))
                .toList();
    }

    private PaymentSummary toSummary(UUID tenantId, PaymentIntent intent) {
        List<PaymentAttempt> intentAttempts = attempts.listForIntent(tenantId, intent.id());
        boolean uncertain =
                intentAttempts.stream().anyMatch(attempt -> attempt.status() == PaymentAttemptStatus.UNCERTAIN);

        // The fiscal answer is the most recent document for the order, whatever kind
        // it is. There may legitimately be several — a sale and its cancel — and the
        // summary reports the current position rather than pretending there is one.
        List<FiscalDocument> fiscalDocuments = documents.listForOrder(tenantId, intent.orderId());
        FiscalStatus fiscalStatus =
                fiscalDocuments.isEmpty() ? null : fiscalDocuments.getLast().status();
        String fiscalReason =
                fiscalDocuments.isEmpty() ? null : fiscalDocuments.getLast().reasonCode();

        return new PaymentSummary(
                intent.id(),
                intent.orderId(),
                intent.tender().name(),
                intent.method().code(),
                intent.providerType() == null ? null : intent.providerType().name(),
                intent.status().name(),
                intent.status() == PaymentIntentStatus.PAID,
                uncertain,
                intent.requiresPaymentBeforeConfirmation(),
                intent.amount().value(),
                transactions.capturedMinor(tenantId, intent.id()),
                transactions.returnedMinor(tenantId, intent.id()),
                intent.amount().currency(),
                fiscalStatus == null ? null : fiscalStatus.name(),
                fiscalReason);
    }
}
