package uz.horecaos.platform.payments.application;

import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.api.FiscalReceiptSource;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/** Hands a hand-wired test the package-private {@code FiscalReceiptReader}, which is a Spring bean in production. */
public final class FiscalReceiptReaderFactory {

    private FiscalReceiptReaderFactory() {}

    public static FiscalReceiptSource create(
            PaymentFiscalService fiscal,
            JdbcFiscalDocumentStore documents,
            JdbcPaymentIntentStore intents,
            OrderDirectory orders,
            FieldProtection protection,
            ObjectMapper json) {
        return new FiscalReceiptReader(fiscal, documents, intents, orders, protection, json);
    }
}
