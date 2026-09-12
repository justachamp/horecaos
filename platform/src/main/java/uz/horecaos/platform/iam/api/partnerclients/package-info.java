/**
 * Contracts other modules use for a partner API client's Keycloak principal (ADR 0106).
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on a parent
 * package does not cover its sub-packages, so without this the types here are
 * internal to {@code iam} and no other module — {@code partner} above all —
 * can reference them, the same gap ADR 0009 records for {@code
 * iam.api.organizations}.
 */
@org.springframework.modulith.NamedInterface("partnerclients")
package uz.horecaos.platform.iam.api.partnerclients;
