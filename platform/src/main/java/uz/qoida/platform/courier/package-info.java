/**
 * Courier compensation, shifts, and settlement (ADR 0042).
 *
 * <p>A module of its own rather than a corner of {@code fulfillment}, for the
 * reason ADR 0041 separated the kitchen. Fulfillment holds zones, tariffs, and
 * the delivery charge a customer pays, and changes when pricing changes. This
 * holds an engagement, hours, an append-only ledger, and a settlement statement,
 * and changes when a labour arrangement or a finance calendar changes. The two
 * must never read each other: ADR 0042's central rule is that courier earnings
 * never derive from the customer delivery charge, and the cheapest way to keep
 * that true is that the code computing one cannot see the other.
 *
 * <p>The tables are named {@code fulfillment.courier_*} because ADR 0042 names
 * them that, and a schema that does not match the document describing it is a
 * document nobody trusts. A shared schema is not a shared reason to change.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Courier compensation")
package uz.qoida.platform.courier;
