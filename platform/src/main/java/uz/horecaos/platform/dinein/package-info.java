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
 * <p>The dependency runs one way. This module reads {@code ordering.orders} to
 * total a session and never writes one; ordering knows nothing about a table.
 * That is what keeps a dine-in order identical to every other order in pricing,
 * inventory, fiscal treatment, audit, and reporting, which is the whole benefit
 * ADR 0047 bought by refusing a parallel aggregate.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Dine-in")
package uz.horecaos.platform.dinein;
