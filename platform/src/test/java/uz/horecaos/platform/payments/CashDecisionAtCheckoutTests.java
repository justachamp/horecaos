package uz.horecaos.platform.payments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.application.PaymentBusinessCalendar;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.application.PaymentIntentService;
import uz.horecaos.platform.payments.application.PaymentLegalEntityResolver;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * What a cash checkout writes about the receipt (ADR 0166 «Providers», ADR 0038 stage 5).
 *
 * <p>Cash was always «not applicable»: no provider can fiscalize it, and the decision was recorded as a row so that
 * reversing it is a query and not archaeology. That stays true where nothing else can discharge the receipt. Where
 * the location has fiscal equipment of its own, a cash sale is a {@code TERMINAL} receipt and «not applicable»
 * would be false, so no decision row is written and the fiscal module opens the real obligation when the order
 * completes. The checkout is where the two diverge, so the checkout is what is pinned.
 */
class CashDecisionAtCheckoutTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

    private final JdbcPaymentIntentStore intents = mock(JdbcPaymentIntentStore.class);
    private final PaymentFiscalService fiscal = mock(PaymentFiscalService.class);

    private PaymentIntentService service(boolean terminalAtTheLocation) {
        OrderDirectory orders = mock(OrderDirectory.class);
        when(orders.summary(TENANT, ORDER))
                .thenReturn(Optional.of(new OrderDirectory.OrderSummary(
                        ORDER, TENANT, BRAND, LOCATION, "A-1", null, "guest", "CONFIRMED", "UZS", 15_000L, 1)));
        PaymentLegalEntityResolver sellers = (tenantId, locationId, businessDate) -> Optional.of(UUID.randomUUID());
        PaymentBusinessCalendar calendar = (tenantId, locationId, at) -> LocalDate.of(2026, 10, 23);
        return new PaymentIntentService(
                intents,
                orders,
                sellers,
                mock(PaymentBindingResolver.class),
                calendar,
                fiscal,
                (tenantId, locationId) -> terminalAtTheLocation && LOCATION.equals(locationId),
                event -> {},
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("cash at a branch with no fiscal equipment still records the 2026-08-22 decision")
    void cashWithNoTerminalRecordsTheDecision() {
        service(false).createIntent(TENANT, ORDER, 15_000L, "UZS", "CASH", "idem-1");

        verify(fiscal).recordCashNotApplicable(any(), any());
    }

    @Test
    @DisplayName("cash at a branch with a capable terminal records no «not applicable»: the terminal owes the receipt")
    void cashAtATerminalBranchRecordsNoDecision() {
        service(true).createIntent(TENANT, ORDER, 15_000L, "UZS", "CASH", "idem-1");

        verify(fiscal, never()).recordCashNotApplicable(any(), any());
    }
}
