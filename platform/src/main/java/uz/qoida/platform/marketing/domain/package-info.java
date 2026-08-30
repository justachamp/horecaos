/**
 * The vocabulary of ADR 0044: what an audience may ask, what refuses a message,
 * how a campaign moves, and what an SMS costs.
 *
 * <p>Nothing here reaches a database, a provider, or a clock. The predicate
 * catalogue and the segment counter in particular are pure, because both are
 * things a marketer is shown a number from and an invoice is later read against,
 * and a definition that needs infrastructure to test is a definition nobody
 * tests at the edges.
 *
 * <p>The catalogue is closed on purpose. ADR 0018's argument against scriptable
 * price rules applies here with one addition: a marketer with arbitrary query
 * access over the customer tables has arbitrary read of the tenant's base.
 * Extending the catalogue is a schema and code change, and that is the trade.
 */
package uz.qoida.platform.marketing.domain;
