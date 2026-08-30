/**
 * What the platform measures about itself, and the probes an operator and a
 * watchdog ask questions of (ADR 0023).
 *
 * <p>A module of its own rather than a corner of {@code web}, and the reason is
 * what the code in here is. {@code web} holds cross-cutting concerns of an HTTP
 * request — correlation, idempotency, capability shadowing, the error contract —
 * and every class in it sits on a request path. Nothing here does. These classes
 * poll tables on a timer and publish gauges; the only HTTP surface the module
 * owns is a set of Actuator health <em>groups</em>, which are configuration
 * rather than controllers. Filing them under {@code web} would put a scheduled
 * database poll in the package a reader opens to find out what happens to a
 * request.
 *
 * <p>The module reads other modules' tables through {@link
 * org.springframework.jdbc.core.simple.JdbcClient} and imports no other module's
 * types. That is deliberate: an observer that compiles against a domain module
 * makes that module harder to change in order to keep a graph drawing, and
 * Modulith would be right to reject the dependency. The cost is that a table
 * rename breaks a gauge at runtime rather than at compile time, which the
 * integration test in {@code ObservabilityMetricsTests} is there to catch.
 *
 * <p><strong>ADR 0029 applies to every line of this module without exception.</strong>
 * No metric carries a customer identifier, a phone number, an address, or a
 * tenant identifier. Tenant, brand, and location are log and trace fields, which
 * is a different thing with different retention and a different audience. A
 * metric labelled by customer is a privacy incident with a dashboard in front of
 * it, and the label sets below are all closed sets the schema already constrains.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Observability")
package uz.horecaos.platform.observability;
