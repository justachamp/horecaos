/**
 * Brand-authored storefront content (ADR 0157): what a brand puts in front of its customers
 * besides products.
 *
 * <p>One item model for every kind, in two tiers. Tier 1 is bounded merchandising: banners, promo
 * cards and home product groups, then stories and pop-ups. Tier 2 is editorial: news, gallery
 * albums, recipes and a vacancy <em>listing</em>, which a brand does not have until the platform
 * owner switches the {@code content.editorial.enabled} entitlement on for its tenant <em>and</em>
 * the brand's {@code content.editorial} policy names the kind.
 *
 * <p>An item has at most one mutable draft and any number of immutable revisions. Publishing
 * writes the draft as the next revision and points the item at it; <em>scheduled</em>, <em>live</em>
 * and <em>expired</em> are never stored, they are computed from the live revision's window at the
 * moment of every read. Text is a typed document, never markup: a block-based rich text model with
 * a server-side sanitizer, so there is nothing to bypass.
 *
 * <p>The public read is one document, the same for everyone and cacheable: it carries no personal
 * data. ADR 0112's personalised offers stay a separate, authenticated read and never enter it.
 *
 * <p>Boundaries, all one way: {@code media.api} (the creative read), {@code tenancy.api} (brand
 * languages, channels, policies, the brand and its verified hostnames), {@code iam.api}
 * (capabilities, the current actor, entitlements through {@code EntitlementGate}, so there is no
 * edge to {@code commercial}) and {@code audit.api}. {@code catalog} and {@code pricing}
 * implement the two small ports this module declares in its own {@code api}
 * ({@link uz.horecaos.platform.content.api.ContentTargetLookup},
 * {@link uz.horecaos.platform.content.api.PromotionLiveLookup}), the direction every port of this
 * kind points.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Content")
package uz.horecaos.platform.content;
