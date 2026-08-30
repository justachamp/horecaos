/**
 * The reporting module's services (ADR 0043): the business-day policy, the close
 * job that builds day-grain facts, the settle recut that reconciles them against
 * {@code ordering}, and the typed query that is the only way a client asks for a
 * number.
 *
 * <p>Two rules divide this package from the rest of the platform.
 *
 * <p>The close job is the <em>only</em> thing here that reads a module schema. It
 * reads {@code ordering} and {@code payments} and writes nothing but
 * {@code reporting}. The read path — everything a report or an API response goes
 * through — touches {@code reporting} alone, which ADR 0043 turns from a
 * convention into a grant: {@code qoida_reporting_read} holds SELECT on that
 * schema and nothing else.
 *
 * <p>No aggregate is composed outside the metric registry. A caller names metric
 * ids and dimensions; it never sends SQL, an expression, or a fragment of one.
 * The moment a client can send an expression the registry becomes decoration and
 * the disagreement ADR 0043 exists to prevent returns through the front door.
 */
package uz.qoida.platform.reporting.application;
