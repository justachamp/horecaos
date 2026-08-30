package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.TransitionRow;

/**
 * Reading orders (ADR 0019).
 *
 * <p>Reads only the snapshot tables. Nothing here joins back to the catalog or
 * the price books, which is what makes "the order says what it said" a property
 * of the schema rather than a promise: there is no live row a republish could
 * change under a receipt.
 *
 * <p>Every read carries the platform warnings that apply, so an unwired port
 * shows up on an operations screen rather than only in a startup log.
 */
@Service
public class OrderQueryService {

    private static final String ORDER_LINE_TABLE = "ordering.order_lines";
    private static final String NOTE_COLUMN = "note_encrypted";

    private final JdbcOrderStore orders;
    private final JdbcOrderProcessStore processes;
    private final PaymentIntentPort payments;
    private final FieldProtection protection;

    public OrderQueryService(
            JdbcOrderStore orders,
            JdbcOrderProcessStore processes,
            PaymentIntentPort payments,
            FieldProtection protection) {
        this.orders = orders;
        this.processes = processes;
        this.payments = payments;
        this.protection = protection;
    }

    /**
     * Reveals one line's customer note (ADR 0029).
     *
     * <p>Separate from reading the order, and requiring a stated purpose, because
     * "no onions, ring the top bell" is the customer's own words about themselves.
     * A kitchen ticket needs it; an order list does not, and rendering it
     * everywhere would put personal data on every screen in the branch.
     */
    @Transactional(readOnly = true)
    public Optional<String> revealLineNote(UUID tenantId, UUID orderId, UUID lineId, String purpose) {
        return orders.lineNote(tenantId, orderId, lineId)
                .map(stored -> protection.reveal(
                        tenantId,
                        ProtectedValue.deserialize(stored),
                        new FieldProtection.RecordRef(ORDER_LINE_TABLE, NOTE_COLUMN, lineId),
                        purpose));
    }

    @Transactional(readOnly = true)
    public Optional<OrderDetail> detail(UUID tenantId, UUID orderId) {
        return detail(tenantId, orderId, null);
    }

    /**
     * The order as it stood at one revision (ADR 0039).
     *
     * <p>ADR 0039's own negative consequence names this as the trap: revisioned
     * orders make every read revision-aware, and a read that forgets to pin one
     * double-counts. The pin is a parameter here rather than a convention, so a
     * caller has to say which answer it wants.
     *
     * @param revision the revision to read at, or null for the current one
     */
    @Transactional(readOnly = true)
    public Optional<OrderDetail> detail(UUID tenantId, UUID orderId, Integer revision) {
        return orders.find(tenantId, orderId).map(order -> {
            List<OrderLineRow> lines = orders.lines(tenantId, orderId, revision);
            Map<UUID, List<OrderModifierRow>> modifiers = orders.lineModifiers(tenantId, orderId).stream()
                    .collect(Collectors.groupingBy(OrderModifierRow::orderLineId));

            List<DetailLine> detailLines = new ArrayList<>(lines.size());
            lines.forEach(
                    line -> detailLines.add(new DetailLine(line, modifiers.getOrDefault(line.lineId(), List.of()))));

            return new OrderDetail(order, detailLines, warnings());
        });
    }

    /**
     * An order scoped to the customer who placed it.
     *
     * <p>The ownership predicate is a parameter of the query, not a check after
     * loading: ADR 0019 requires storefront lookup to verify customer or guest
     * proof, and a post-load check is one forgotten branch away from serving
     * somebody else's order.
     */
    @Transactional(readOnly = true)
    public Optional<OrderDetail> detailForCustomer(
            UUID tenantId, UUID orderId, UUID customerAccountId, String guestReferenceHash) {
        return detail(tenantId, orderId).filter(found -> {
            OrderRow order = found.order();
            if (customerAccountId != null) {
                return customerAccountId.equals(order.customerAccountId());
            }
            return guestReferenceHash != null && guestReferenceHash.equals(order.guestReferenceHash());
        });
    }

    @Transactional(readOnly = true)
    public List<OrderRow> forLocation(UUID tenantId, UUID brandId, UUID locationId, List<String> statuses, int limit) {
        return orders.listForLocation(tenantId, brandId, locationId, statuses, limit);
    }

    /**
     * A page of one customer's own orders (ADR 0019, ADR 0031).
     *
     * <p>The account is a predicate of the query, exactly as it is in
     * {@link #detailForCustomer}. It is the difference between a customer's history
     * and an enumeration of the brand's: there is no unscoped form of this method,
     * and no parameter that widens it.
     *
     * <p>One row per order and nothing beneath it. Lines, modifiers and the
     * transition log are all read through {@code detail}, which is a second request
     * a client makes for the order it opened — because a list that eagerly loaded
     * them would be N+2 queries and would carry a customer's own line notes onto a
     * screen that only shows totals.
     *
     * @param cursorOrderId the last order of the previous page, or null for the
     *                      first. Resolved inside the caller's own scope
     * @throws UnknownCursorException when the cursor names no order of this
     *                                customer's at this brand — including one that
     *                                is somebody else's, which answers identically
     */
    @Transactional(readOnly = true)
    public List<JdbcOrderStore.CustomerOrderRow> forCustomer(
            UUID tenantId, UUID brandId, UUID accountId, UUID cursorOrderId, int limit) {

        Instant before = null;
        if (cursorOrderId != null) {
            before = orders.customerOrderCursor(tenantId, brandId, accountId, cursorOrderId)
                    .orElseThrow(UnknownCursorException::new);
        }
        return orders.listForCustomer(tenantId, brandId, accountId, before, cursorOrderId, limit);
    }

    /** The cursor names nothing this caller may continue from. */
    public static class UnknownCursorException extends RuntimeException {
        public UnknownCursorException() {
            super("This cursor does not name an order of yours");
        }
    }

    /** The transition log: the answer to "why is this order in this state". */
    @Transactional(readOnly = true)
    public List<TransitionRow> timeline(UUID tenantId, UUID orderId) {
        return orders.history(tenantId, orderId);
    }

    /**
     * Every revision of one order, with the delta each one carried (ADR 0039).
     *
     * <p>Revision 1 is the checkout snapshot and is byte-identical for ever, which
     * is what makes a report pinned to it reconcile to the original total however
     * many amendments followed.
     */
    @Transactional(readOnly = true)
    public List<JdbcOrderStore.RevisionRow> revisions(UUID tenantId, UUID orderId) {
        return orders.revisions(tenantId, orderId);
    }

    /** The one terminal outcome, once the order has ended. */
    @Transactional(readOnly = true)
    public Optional<JdbcOrderStore.OutcomeRow> outcome(UUID tenantId, UUID orderId) {
        return orders.findOutcome(tenantId, orderId);
    }

    /** Process managers that need an operator, per tenant. */
    @Transactional(readOnly = true)
    public List<JdbcOrderProcessStore.ProcessRow> stuckProcesses(UUID tenantId, int limit) {
        return processes.stuck(tenantId, limit);
    }

    private List<String> warnings() {
        return payments.isWired() ? List.of() : List.of(PaymentIntentPort.NOT_WIRED_WARNING);
    }

    public record OrderDetail(OrderRow order, List<DetailLine> lines, List<String> warnings) {}

    public record DetailLine(OrderLineRow line, List<OrderModifierRow> modifiers) {}
}
