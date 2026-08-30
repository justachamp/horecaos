/**
 * The marketplace channel and the partner API (ADR 0040).
 *
 * <p>A module of its own rather than a corner of {@code integration} or of
 * {@code ordering}, and the reason is the threat model rather than taxonomy.
 * Everything here serves a principal that is a machine belonging to somebody
 * else: an aggregator authenticates with client credentials, is rate-limited per
 * partner, and must not be able to read another partner's orders or another
 * tenant's anything. {@code integration} is where Qoida calls out and holds the
 * secret; this is where somebody else calls in and holds theirs. Putting the two
 * in one module would put an inbound public surface behind the same reasoning
 * that guards an outbound adapter, and they need opposite defaults.
 *
 * <p>It is not part of {@code ordering} for the reason ADR 0040 gives for the
 * order columns: an inbound aggregator order <em>is</em> an order, and the whole
 * decision is that it stays in {@code ordering.orders} rather than becoming a
 * second aggregate. What lives here is the partner relationship — credentials,
 * ingestion, staging evidence, external identifiers, handover proof, liveness —
 * and none of that is something a Qoida checkout ever needs.
 *
 * <p>One seam is deliberate and temporary. {@code MarketplaceOrderIntake} writes
 * {@code ordering.orders}, which is another module's table. The right long-term
 * home for that write is an intake port published by {@code ordering} itself,
 * and this adapter is the side of the seam that moves when it exists. It is here
 * rather than there because the alternative — a separate marketplace aggregate
 * that projects into the order list — is the alternative ADR 0040 rejected, and
 * a temporary adapter is a cheaper thing to be wrong about than a second order
 * table.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Partner")
package uz.qoida.platform.partner;
