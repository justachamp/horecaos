package uz.horecaos.platform.fiscal.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderMarksPort.LineMarkCount;

/**
 * The fiscal evidence surface's {@link OrderMarkCounts}, answered from ADR 0161's mark store through
 * {@link OrderMarksPort#markCounts} (ADR 0166 Decision 7).
 *
 * <p>Counts and nothing else: the port never hands back a code on this read, and neither does this. An
 * order with no marked line answers {@code 0 of 0}, which is a different statement from the stand-in
 * {@link FiscalPortConfiguration} supplies where there is no mark store at all («marks not built»); a
 * tenant with {@code feature.marked_goods} off answers {@code 0 of 0} too, because the port reports no
 * marked line for it.
 */
@Component
public class OrderMarksOrderMarkCounts implements OrderMarkCounts {

    private final OrderMarksPort marks;

    public OrderMarksOrderMarkCounts(OrderMarksPort marks) {
        this.marks = marks;
    }

    @Override
    public Optional<Totals> forOrder(UUID tenantId, UUID orderId) {
        int required = 0;
        int captured = 0;
        for (LineMarkCount line : marks.markCounts(tenantId, orderId)) {
            required += line.required();
            // A line can hold more live marks than it needs only if its quantity was reduced after the
            // scan; the evidence reads "all that are required", never more than that.
            captured += Math.min(line.captured(), line.required());
        }
        return Optional.of(new Totals(required, captured));
    }
}
