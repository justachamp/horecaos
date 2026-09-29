package uz.horecaos.platform.dinein.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which table each of a page of orders was eaten at (ADR 0047, gap map rows
 * {@code 10.5b}/{@code X.36}'s dine-in visibility follow-up).
 *
 * <p>An order carries no table of its own -- ADR 0047 refuses a table label on
 * the order, and refuses ordering knowing anything about a room -- so a
 * screen that wants to say "table 7" beside an order has to ask the module that
 * owns the fact. This is that question, batched: the operations order board, the
 * order detail and the kitchen ticket all render a table beside an order, and
 * none of them may pay a query per row for it.
 *
 * <p>The same boundary {@code ActiveCourierAssignmentsPort} and {@code
 * CourierEtaPort} draw for fulfilment: the consumer asks a port, the owning
 * module answers from its own tables, and no consumer holds a copy.
 *
 * <p>Answers only the table's own code and display name plus the session id --
 * facts about a room, never about a guest. No name, phone, party size or bill
 * total crosses this line (ADR 0029).
 */
public interface OrderTablesPort {

    /**
     * The table (or joined tables) and session behind each order, keyed by order
     * id. An order absent from the result was not placed through a table session
     * -- a delivery, a pickup, or a DINE_IN order an operator keyed in without
     * seating anyone -- and reads identically to a caller that only wants "which
     * table, if any".
     *
     * <p>The tenant is a predicate of the statement, never a check afterwards: an
     * order id that belongs to another tenant answers as if it did not exist.
     *
     * @param orderIds may be empty, in which case nothing is queried
     */
    Map<UUID, OrderTable> tablesByOrders(UUID tenantId, Collection<UUID> orderIds);

    /**
     * The seated party an order belongs to: its session and the tables that
     * session occupies, in the order they were joined. More than one table is a
     * party pushed together for a large group.
     */
    record OrderTable(UUID sessionId, List<TableRef> tables) {

        public OrderTable {
            tables = List.copyOf(tables);
        }
    }

    /** One physical table: its stable code (printed on the QR card) and display name. */
    record TableRef(UUID tableId, String code, String displayName) {}
}
