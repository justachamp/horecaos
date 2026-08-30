/**
 * Contracts other modules use to confer platform-side authority (ADR 0025).
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on a parent
 * package does not cover its sub-packages, so without this the types here are
 * internal to {@code iam} and no other module can reference them — the same
 * gap ADR 0009 records for {@code iam.api.organizations}.
 */
@org.springframework.modulith.NamedInterface("grants")
package uz.horecaos.platform.iam.api.grants;
