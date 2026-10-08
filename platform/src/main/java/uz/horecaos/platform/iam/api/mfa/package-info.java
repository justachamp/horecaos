/**
 * Staff multi-factor authentication as other modules see it (ADR 0148): the three
 * values a tenant's second-factor setting can take, the port {@code tenancy}
 * implements to answer it, and the administrative surface {@code audit} drives
 * for a platform account's reset. Keycloak holds and verifies the factor; nothing
 * here carries a secret, a code or an authenticator label.
 */
@org.springframework.modulith.NamedInterface("mfa")
package uz.horecaos.platform.iam.api.mfa;
