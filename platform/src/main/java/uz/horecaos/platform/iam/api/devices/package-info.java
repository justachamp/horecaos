/**
 * Contracts other modules use for the ADR 0079 device principal.
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on a parent
 * package does not cover its sub-packages, so without this the types here are
 * internal to {@code iam} and no other module — {@code kitchen} above all —
 * can reference them, the same gap ADR 0009 records for {@code
 * iam.api.organizations}.
 */
@org.springframework.modulith.NamedInterface("devices")
package uz.horecaos.platform.iam.api.devices;
