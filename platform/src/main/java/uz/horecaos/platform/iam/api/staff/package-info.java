/**
 * Contracts other modules use for a tenant's own record of its staff
 * (ADR 0139): who a Keycloak subject is to one tenant.
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on a parent
 * package does not cover its sub-packages, so without this the types here are
 * internal to {@code iam} and no other module can reference them.
 */
@org.springframework.modulith.NamedInterface("staff")
package uz.horecaos.platform.iam.api.staff;
