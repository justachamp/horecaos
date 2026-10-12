package uz.horecaos.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fiscal.application.FiscalPortConfiguration;
import uz.horecaos.platform.fiscal.application.OrderMarkCounts;
import uz.horecaos.platform.fiscal.application.OrderMarkCounts.Totals;
import uz.horecaos.platform.fiscal.application.OrderMarksOrderMarkCounts;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderMarksPort.LineMarkCount;

/**
 * The evidence block reads the mark store through the port, as counts (ADR 0166 Decision 7, ADR 0161).
 */
class OrderMarksOrderMarkCountsTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a1");
    private static final UUID ORDER = UUID.fromString("018f9f10-9000-7000-8000-0000000000e1");

    @Test
    @DisplayName("the totals are the sum over the marked lines, and a line never reads as more than it needs")
    void sumsTheLinesAndCapsEachAtWhatItNeeds() {
        OrderMarkCounts counts = adapterOver(List.of(
                new LineMarkCount(UUID.randomUUID(), 3, 2),
                new LineMarkCount(UUID.randomUUID(), 1, 1),
                new LineMarkCount(UUID.randomUUID(), 2, 5)));

        assertThat(counts.forOrder(TENANT, ORDER)).contains(new Totals(6, 5));
    }

    @Test
    @DisplayName("an order with no marked line is 0 of 0, which is not the same as no mark store")
    void anUnmarkedOrderIsZeroOfZero() {
        assertThat(adapterOver(List.of()).forOrder(TENANT, ORDER)).contains(new Totals(0, 0));
    }

    @Test
    @DisplayName("where no mark store is wired the stand-in says so, which is not 0 of 0")
    void theStandInIsEmptyWhichTheEvidenceShowsAsNotBuilt() {
        assertThat(new FiscalPortConfiguration().unbuiltOrderMarkCounts().forOrder(TENANT, ORDER))
                .isEmpty();
    }

    private static OrderMarkCounts adapterOver(List<LineMarkCount> lines) {
        OrderMarksPort port = new OrderMarksPort() {
            @Override
            public List<LineMarkCount> markCounts(UUID tenantId, UUID orderId) {
                return lines;
            }

            @Override
            public ReceiptSource receiptSource(UUID tenantId, UUID orderId) {
                throw new AssertionError("the evidence read asks for counts and never for the receipt source");
            }
        };
        return new OrderMarksOrderMarkCounts(port);
    }
}
