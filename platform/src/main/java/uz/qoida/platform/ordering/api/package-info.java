/**
 * What ordering exposes to other modules: the business facts it publishes, and
 * the ports it needs other modules to implement (ADR 0019).
 *
 * <p>No cart, order, or process-state type appears here. A consumer that needs
 * more than an identifier and a total calls an authorized API, per ADR 0032.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.qoida.platform.ordering.api;
