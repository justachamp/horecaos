/**
 * Contracts other modules implement or consume to take part in integration.
 *
 * <p>The inbox handler contract belongs here rather than beside its
 * implementation: any module that consumes an event has to implement it, so
 * keeping it internal to {@code integration} would mean no module could have a
 * consumer at all.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.integration.api;
