/**
 * The vocabulary of ADR 0024: where a scope stands, who owns its writes and
 * reads, and which moves between those positions exist at all.
 *
 * <p>The one member of that vocabulary that is not here is {@code
 * MigrationCapability}, which lives in {@code migration.api}: naming a capability
 * is how another module asks the ownership question at all, so it is published
 * rather than internal. Everything remaining in this package is read by the
 * control plane, by the fencing gate and by the operator console, and by nothing
 * outside the module.
 *
 * <p>Nothing here reaches a database, a provider, or a clock. The states, the
 * transition table, and the coherence rules on the ownership modes are the parts
 * of the migration that must be identical in the control plane, in the fencing
 * gate every other module calls, and in the operator console, so they live in
 * one place with no runtime of their own.
 */
package uz.horecaos.platform.migration.domain;
