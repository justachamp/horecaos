/**
 * The order's fiscal obligation: whether a receipt exists, and what is stopping
 * one (ADR 0038).
 *
 * <p>A module of its own rather than a corner of {@code payments}, and ADR 0038
 * gives the reason in one sentence: fiscalization is an obligation of the order,
 * not of the payment. A cash order — the majority tender in this market — has a
 * receipt obligation and no Qoida payment transaction to hang it on, so a design
 * that files fiscalization under payments leaves {@code payment_transaction_id}
 * null exactly where the obligation is hardest.
 *
 * <p>What lives here is the part of the lifecycle that is about <em>time</em>.
 * The payments module owns the two provider adapters and the inbound callbacks;
 * it can tell a receipt from a rejection the moment either arrives. What it
 * cannot do, by construction, is tell a receipt that is late from one that is
 * never coming: Payme's {@code SetFiscalData} is inbound and optional to
 * implement, and there is no merchant-initiated retry on that path at all. So
 * this module ages an unanswered document into {@code BLOCKED} on a deadline and
 * puts it in front of a person, and reports coverage in a shape that cannot
 * present an unreceipted majority as though it were fine.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Fiscal")
package uz.qoida.platform.fiscal;
