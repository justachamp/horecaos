package uz.horecaos.platform.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fiscal.api.FiscalTerminalDirectory;
import uz.horecaos.platform.payments.domain.PaymentMethod;

/**
 * ADR 0038: which party discharges a tender's fiscal obligation (wave P34).
 *
 * <p>Exercises {@link CheckoutSettlementPlanner#responsibilityOf} directly
 * against a fake {@link FiscalTerminalDirectory}, the same way a pure switch
 * statement is tested elsewhere in this codebase — nothing here needs a
 * tenant, a cart, or a channel matrix, and building one just to reach a
 * {@code switch} would be the kind of fixture that proves the fixture rather
 * than the code.
 */
class CheckoutSettlementPlannerFiscalResponsibilityTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CAPABLE_LOCATION = UUID.randomUUID();
    private static final UUID BARE_LOCATION = UUID.randomUUID();

    /**
     * {@code store} and {@code settlements} are never touched by {@code
     * responsibilityOf} — it is a pure switch over the payment method and one
     * directory lookup — so {@code null} is safe here and honest: a real
     * instance would need a tenant, a cart and a channel matrix just to reach
     * a method this test calls directly.
     */
    @SuppressWarnings("NullAway")
    private final CheckoutSettlementPlanner planner = new CheckoutSettlementPlanner(
            null,
            null,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            (tenantId, locationId) -> tenantId.equals(TENANT) && locationId.equals(CAPABLE_LOCATION));

    @Test
    void cashIsTerminalOnceACapableTerminalIsBound() {
        assertThat(planner.responsibilityOf(TENANT, CAPABLE_LOCATION, PaymentMethod.CASH))
                .as("ADR 0038: cash is discharged by the location's own fiscal-capable equipment")
                .isEqualTo("TERMINAL");
    }

    @Test
    void cashFallsBackToOperatorWhereNoTerminalIsBound() {
        assertThat(planner.responsibilityOf(TENANT, BARE_LOCATION, PaymentMethod.CASH))
                .as("declaring TERMINAL here would assert equipment this location has not registered")
                .isEqualTo("OPERATOR");
    }

    @Test
    void aClickOrPaymeTenderIsAlwaysPartnerRegardlessOfTerminals() {
        assertThat(planner.responsibilityOf(TENANT, BARE_LOCATION, PaymentMethod.CLICK))
                .isEqualTo("PARTNER");
        assertThat(planner.responsibilityOf(TENANT, CAPABLE_LOCATION, PaymentMethod.PAYME))
                .isEqualTo("PARTNER");
    }

    @Test
    void aMarketplaceTenderIsNeverTheTenantsOwnObligation() {
        assertThat(planner.responsibilityOf(TENANT, BARE_LOCATION, PaymentMethod.MARKETPLACE))
                .isEqualTo("MARKETPLACE");
    }
}
