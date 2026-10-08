package uz.horecaos.platform.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which storefront paths the platform promises to keep, decided path by path (ADR 0070).
 *
 * <p>The {@code storefront} OpenAPI group is the contract a vendor builds against, and a
 * contract that freezes everything it happens to contain freezes things nobody meant to
 * promise. So membership of the published surface is a list a person edits on purpose, not a
 * property a controller has by being mounted under {@code /api/v1/storefront/}: a path is
 * {@code published} because it is named in {@link #PUBLISHED}, {@code internal} because it is
 * named in {@link #INTERNAL} with a reason, and a path in neither fails
 * {@code StorefrontContractTests} until somebody decides.
 *
 * <p>The document carries the answer as an {@code x-horecaos-surface} extension on every
 * operation, and the compatibility gate holds exactly the published ones to the never-remove,
 * additive-only promise. An internal path stays in the document, and in the generated client the
 * platform's own storefront uses, but a vendor is told in the document itself that it may change
 * without notice.
 *
 * <p>The internal set is ADR 0070's own recommendation, nothing wider: the channel-specific
 * routes bound to one surface rather than to the commerce every storefront shares.
 */
final class StorefrontPublishedSurface {

    private static final String ROOT = "/api/v1/storefront";
    private static final String TENANT = ROOT + "/tenants/{tenantId}";
    private static final String BRAND = TENANT + "/brands/{brandId}";
    private static final String LOCATION = BRAND + "/locations/{locationId}";
    private static final String CART = BRAND + "/carts/{cartId}";
    private static final String ORDER = BRAND + "/orders/{orderId}";
    private static final String ME = BRAND + "/me";

    /** Paths that are bound to one channel and stay out of the promise, with the reason for each. */
    static final Map<String, String> INTERNAL = internal();

    /** Every path the platform promises to keep within v1. */
    static final Set<String> PUBLISHED = Set.of(
            // Who and where: resolving a storefront's tenant from its hostname, and the tenant's own presentation.
            ROOT + "/channel-hostnames/{hostname}",
            // The languages the platform offers, from the registry (ADR 0149): a vendor's language picker.
            ROOT + "/locales",
            TENANT + "/channels/{channel}/pages/{slug}",
            TENANT + "/channels/{channel}/presentation",
            BRAND + "/analytics",
            BRAND + "/support/faq",
            BRAND + "/support/social-links",
            BRAND + "/terms",
            BRAND + "/terms/accept",
            BRAND + "/terms/acceptance-status",
            // The menu, its pictures, and whether one dish can be ordered right now.
            LOCATION + "/menu",
            LOCATION + "/profile",
            TENANT + "/media/{assetId}",
            TENANT + "/locations/{locationId}/variants/{variantId}/availability",
            // Where and how a customer can be served.
            ROOT + "/pickup-locations",
            LOCATION + "/delivery-fee",
            LOCATION + "/fulfillment-modes",
            LOCATION + "/serviceability",
            // The cart, its pricing, and the checkout.
            BRAND + "/carts",
            CART,
            CART + "/destination",
            CART + "/lines/{lineKey}",
            CART + "/location",
            CART + "/payment-method",
            CART + "/payment-methods",
            CART + "/pricing",
            CART + "/promo-code",
            BRAND + "/checkouts",
            // Signing in, and who the customer is.
            BRAND + "/identity/registrations",
            BRAND + "/identity/sessions",
            BRAND + "/identity/sessions/current",
            BRAND + "/identity/verification-challenges",
            BRAND + "/identity/verification-challenges/{challengeId}/attempts",
            ME,
            ME + "/addresses",
            ME + "/addresses/{addressId}",
            ME + "/erasure-request",
            ME + "/erasure-request/cancel",
            ME + "/favourites",
            ME + "/favourites/{productId}",
            // Asking the restaurant to ring back (ADR 0111): a lead, not an order.
            ME + "/callback-requests",
            // Orders, paying for them, and what follows.
            BRAND + "/orders",
            ORDER,
            ORDER + "/cancellations",
            ORDER + "/payment-sessions",
            ORDER + "/reorder",
            ORDER + "/review",
            BRAND + "/reviews",
            // What the customer earns.
            ROOT + "/loyalty/tenants/{tenantId}/accounts/{accountId}",
            ROOT + "/loyalty/tenants/{tenantId}/accounts/{accountId}/entries",
            BRAND + "/referrals/me",
            BRAND + "/referrals/redemptions",
            // The banners the restaurant chose to show this guest, and the guest's own dismissal (ADR 0112).
            BRAND + "/presented-offers",
            BRAND + "/presented-offers/{presentedOfferId}/dismissals");

    private StorefrontPublishedSurface() {}

    /** True when the platform promises this path; false for a path in neither list as well, so an undecided path is not promised. */
    static boolean isPublished(String path) {
        return PUBLISHED.contains(path);
    }

    static List<String> classified() {
        return java.util.stream.Stream.concat(PUBLISHED.stream(), INTERNAL.keySet().stream())
                .sorted()
                .toList();
    }

    private static Map<String, String> internal() {
        String telegram =
                "Bound to the Telegram channel: its link, mini-app and sign-in routes follow the bot's own flow, not the commerce every storefront shares";
        String dineIn =
                "Bound to the dine-in QR flow: the table token, the guest session and its bill belong to the QR surface, not to a storefront a vendor builds";
        Map<String, String> internal = new LinkedHashMap<>();
        internal.put(BRAND + "/telegram/link", telegram);
        internal.put(BRAND + "/telegram/link-codes", telegram);
        internal.put(BRAND + "/telegram/mini-app-link", telegram);
        internal.put(BRAND + "/telegram/sign-in-codes", telegram);
        internal.put(BRAND + "/telegram/sign-in-codes/{code}", telegram);
        internal.put(ROOT + "/dine-in/qr/token-exchanges", dineIn);
        internal.put(ROOT + "/dine-in/sessions", dineIn);
        internal.put(ROOT + "/dine-in/sessions/{sessionId}", dineIn);
        internal.put(ROOT + "/dine-in/sessions/{sessionId}/bill-requests", dineIn);
        internal.put(ROOT + "/dine-in/sessions/{sessionId}/rounds", dineIn);
        internal.put(CART + "/table", dineIn);
        return Map.copyOf(internal);
    }
}
