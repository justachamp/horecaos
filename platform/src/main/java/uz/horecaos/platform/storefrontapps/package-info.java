/**
 * Storefront apps: who is asking, as distinct from which customer (ADR 0070).
 *
 * <p>A storefront used to identify itself by a tenant and brand id in its own
 * configuration and by nothing else, so the platform could not tell one
 * storefront from another, or from a script. This module is the app tier that
 * was missing: a platform-owned registry of apps, a per-brand authorisation a
 * tenant can grant and revoke, and the check that every request on the
 * storefront surface names a registered, authorised app.
 *
 * <p>A module of its own rather than a corner of {@code integration} or of
 * {@code customers}, for the reason {@code partner} gives: what lives here
 * serves somebody else's software. {@code integration} is where HorecaOS calls
 * out and holds the secret; {@code customers} is where a person signs in. An
 * app is neither, and it is checked in addition to the customer's session,
 * never instead of it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Storefront apps")
package uz.horecaos.platform.storefrontapps;
