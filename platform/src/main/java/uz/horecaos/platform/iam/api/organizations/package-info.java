/**
 * Contracts other modules use for organizations.
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on a parent
 * package does not cover its sub-packages, so without this the types here are
 * internal to {@code iam} and no other module can reference them.
 */
@org.springframework.modulith.NamedInterface("organizations")
package uz.horecaos.platform.iam.api.organizations;
