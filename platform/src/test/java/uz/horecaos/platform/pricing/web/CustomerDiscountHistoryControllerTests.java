package uz.horecaos.platform.pricing.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort.Redemption;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort.Redemption.Status;

/**
 * Row 7.9a: {@link CustomerDiscountHistoryController} owns exactly one piece
 * of logic over what {@link CustomerDiscountHistoryPort} hands it — grouping
 * {@code REDEEMED} rows into a per-currency total — and this is that logic's
 * proof, against a fake port rather than a database, the same way {@code
 * SlaBucketControllerTests} proves a controller's own arithmetic.
 */
class CustomerDiscountHistoryControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    @Test
    @DisplayName("totalsRedeemed sums only REDEEMED rows, grouped by currency")
    void totalsRedeemedSumsOnlyRedeemedRowsByCurrency() {
        var uzsOne = redemption(Status.REDEEMED, 5_000L, "UZS");
        var uzsTwo = redemption(Status.REDEEMED, 3_000L, "UZS");
        var usd = redemption(Status.REDEEMED, 200L, "USD");
        var reserved = redemption(Status.RESERVED, 9_999L, "UZS");
        var released = redemption(Status.RELEASED, 8_888L, "UZS");

        var controller =
                new CustomerDiscountHistoryController(fakePort(List.of(uzsOne, uzsTwo, usd, reserved, released)));

        var body = Objects.requireNonNull(controller.history(TENANT, CUSTOMER).getBody());
        assertThat(body.redemptions())
                .as("every row is kept, whatever its status -- the history is the full record")
                .hasSize(5);
        assertThat(body.totalsRedeemed())
                .as("RESERVED and RELEASED amounts never enter the total: neither one was actually "
                        + "given away yet, or ever will be")
                .containsExactlyInAnyOrder(
                        new CustomerDiscountHistoryController.CurrencyTotalResponse("UZS", 8_000L),
                        new CustomerDiscountHistoryController.CurrencyTotalResponse("USD", 200L));
    }

    @Test
    @DisplayName("nothing ever redeemed means an empty breakdown, not a zero-amount row")
    void noRedemptionsMeansAnEmptyBreakdown() {
        var controller = new CustomerDiscountHistoryController(fakePort(List.of()));

        var body = Objects.requireNonNull(controller.history(TENANT, CUSTOMER).getBody());
        assertThat(body.redemptions()).isEmpty();
        assertThat(body.totalsRedeemed()).isEmpty();
    }

    private static Redemption redemption(Status status, long amountMinor, String currency) {
        return new Redemption(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CODE",
                UUID.randomUUID(),
                "A promotion",
                status == Status.RESERVED ? null : UUID.randomUUID(),
                status,
                amountMinor,
                currency,
                NOW,
                status == Status.RESERVED ? null : NOW,
                status == Status.RELEASED ? NOW : null);
    }

    private static CustomerDiscountHistoryPort fakePort(List<Redemption> redemptions) {
        return (tenantId, customerAccountId) -> {
            assertThat(tenantId).isEqualTo(TENANT);
            assertThat(customerAccountId).isEqualTo(CUSTOMER);
            return redemptions;
        };
    }
}
