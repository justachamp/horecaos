/**
 * What the reporting module exposes to other modules: its ADR 0030 configuration keys.
 *
 * <p>Reporting's other seams point the other way -- it reads what a module publishes through that
 * module's own port (ADR 0023) -- so this package is small on purpose.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.reporting.api;
