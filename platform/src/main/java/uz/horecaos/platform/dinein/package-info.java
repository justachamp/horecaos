/**
 * Dine-in: the floor plan, reservations, the table session, and the QR entry
 * point (ADR 0047).
 *
 * <p>A module of its own rather than a corner of {@code ordering}, and the reason
 * is the ADR's central decision rather than taxonomy. Dine-in is a fulfilment
 * mode on the existing order aggregate — plov eaten at table seven and the same
 * plov delivered are one commercial object reaching the guest differently — so
 * there is no second order type here and no second pricing path. What is genuinely
 * new is the room: tables that can be booked and occupied, and a session that
 * accumulates an evening's rounds so that one bill can be presented for several
 * immutable orders.
 *
 * <p>The dependency runs one way for anything that matters. This module reads
 * {@code ordering.orders} to total a session and never writes one; ordering
 * decides nothing from a table. That is what keeps a dine-in order identical to
 * every other order in pricing, inventory, fiscal treatment, audit, and
 * reporting, which is the whole benefit ADR 0047 bought by refusing a parallel
 * aggregate.
 *
 * <p>Two things flow the other way. The first is a label. A screen that shows an
 * order -- the operations board and detail, the kitchen ticket -- has to say
 * which table it is for, and only this module knows. {@link
 * uz.horecaos.platform.dinein.api.OrderTablesPort} is that read: order ids in,
 * table codes and the session id out, tenant a predicate of the statement.
 * The second is a cart bound to a table: {@link
 * uz.horecaos.platform.dinein.api.TableBindingPort} resolves the table a guest's
 * token names, says whether anybody is seated there, and puts an order on that
 * party's bill inside checkout's own transaction, so the round cannot be lost
 * between two requests. Ordering and the kitchen depend on these; this module
 * imports nothing from either at the Java level, so there is no cycle, and {@code
 * ModularArchitectureTests} keeps it that way.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Dine-in")
package uz.horecaos.platform.dinein;
