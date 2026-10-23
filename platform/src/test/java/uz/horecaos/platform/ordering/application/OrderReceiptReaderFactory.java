package uz.horecaos.platform.ordering.application;

import uz.horecaos.platform.ordering.api.OrderReceiptSource;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.QuoteTaxLookup;

/**
 * Hands a hand-wired test the package-private {@code OrderReceiptReader}, the way
 * {@code CheckoutServiceTestFactory} hands out the checkout's collaborators.
 */
public final class OrderReceiptReaderFactory {

    private OrderReceiptReaderFactory() {}

    public static OrderReceiptSource create(JdbcOrderStore orders, QuoteTaxLookup taxes) {
        return new OrderReceiptReader(orders, taxes);
    }
}
