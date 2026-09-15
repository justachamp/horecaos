/**
 * The reporting module's services (ADR 0043): the business-day policy, the close
 * job that builds day-grain facts, the settle recut that reconciles them against
 * {@code ordering}, and the typed query that is the only way a client asks for a
 * number.
 *
 * <p>Two rules divide this package from the rest of the platform.
 *
 * <p>The close job reads a module schema — {@code ordering} and {@code
 * payments} — and writes nothing but {@code reporting}. It was, until a
 * 2026-09-14 review, documented here as the <em>only</em> thing that does: in
 * fact {@link ReportQueryService#tariffAudit}, {@link
 * ReportQueryService#externalDeliveryCost}, {@code ReportQueryService.orders}'s
 * {@code is_preorder} join, and {@link ReportQueryService#cancellationReasons}
 * also read a module schema directly ({@code fulfillment}, {@code ordering},
 * {@code kitchen}) — live, on the request path, not once at close time into a
 * fact. Each is a named, doc'd exception on its own method in {@link
 * uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore},
 * not a silent one, and none is covered by the {@code
 * horecaos_reporting_read} grant ADR 0043 describes below — the running
 * application does not connect as that role. Whether these four should
 * instead be projected into a fact is open, tracked against ADR 0043, not
 * decided by this package existing.
 *
 * <p>No aggregate is composed outside the metric registry. A caller names metric
 * ids and dimensions; it never sends SQL, an expression, or a fragment of one.
 * The moment a client can send an expression the registry becomes decoration and
 * the disagreement ADR 0043 exists to prevent returns through the front door.
 */
package uz.horecaos.platform.reporting.application;
