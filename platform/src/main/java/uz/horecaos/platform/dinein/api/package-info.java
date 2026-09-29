/**
 * What dine-in exposes to other modules: the table an order was eaten at, and the
 * three questions a cart bound to a table has to ask -- which table a guest token
 * names, whether anybody is seated there, and putting an order on that party's bill
 * (ADR 0047). Nothing of the session, the bill or the floor plan itself.
 *
 * <p>Ordering and the kitchen consume {@link uz.horecaos.platform.dinein.api.OrderTablesPort}
 * to label an order with its table, and ordering consumes {@link
 * uz.horecaos.platform.dinein.api.TableBindingPort} to bind a guest's cart to its table
 * and to make the attach part of checkout's own transaction; neither may join
 * {@code dinein.*} itself.
 * The dependency stays one way at the Java level -- dine-in imports nothing from
 * either -- which is what keeps {@code ModularArchitectureTests} free of a cycle
 * and keeps a DINE_IN order identical to every other order everywhere else
 * (pricing, inventory, fiscal treatment, audit, reporting).
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.dinein.api;
