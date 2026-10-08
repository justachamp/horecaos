package uz.horecaos.platform.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import java.util.Map;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppHeaders;
import uz.horecaos.platform.storefrontapps.api.StorefrontContractPolicy;

/**
 * What the {@code storefront} OpenAPI group says about itself (ADR 0070).
 *
 * <p>The group document is the contract a vendor builds a storefront against, so the promises
 * that go with it are in the document, not in prose a vendor has to find elsewhere: which
 * client type they are building and what each one gets, which headers carry the app's
 * identity, which operations are promised and which are internal, and how long a path is
 * served after a replacement is announced.
 *
 * <p>Only the group is described. The full v1 document at {@code /v3/api-docs} is the same
 * running API without this overlay, exactly as ADR 0057 left it.
 */
final class StorefrontContract {

    static final String APP_ID_SCHEME = "storefrontAppId";
    static final String APP_SECRET_SCHEME = "storefrontAppSecret";

    private StorefrontContract() {}

    static void describe(OpenAPI openApi) {
        openApi.getInfo().setDescription("""
                        The HorecaOS storefront contract: the one API every storefront is built against, \
                        ours and a vendor's alike. The core does not change per storefront.

                        ## Which storefront is asking

                        Every request names the app that is asking, in addition to the customer's own session \
                        and never instead of it: the app says which storefront, the session says which customer. \
                        Send the app's id in `%1$s`. Anonymous browsing stays anonymous for the customer, and \
                        requires an app identity all the same, so the platform can meter, rate-limit and revoke.

                        Rollout: the header is accepted and checked now, and a request without it still works \
                        while the platform counts how many arrive unattributed. It becomes required, with notice \
                        under the deprecation policy below, once that count has stayed at zero.

                        ## Public and confidential clients

                        A **public client** is a browser-only storefront. It holds no secret, because a browser \
                        keeps none: it registers an app id and an origin allowlist, and the platform checks each \
                        request's `Origin` against that list. It is attributable and revocable. It is **not \
                        authenticated**, in the sense a browser cannot deliver, and nothing in this contract \
                        says otherwise.

                        A **confidential client** is a server-backed storefront. It registers a secret, sent in \
                        `%2$s`, and gets what that earns: higher quotas and access to any endpoint judged unsafe \
                        for a public client. Never put that secret in a browser bundle; a bundled secret is not one.

                        A tenant authorises an app for a brand and can revoke it. Revoking takes effect on the \
                        app's next request. A refused app is told which thing is wrong: `APP_UNREGISTERED`, \
                        `APP_SUSPENDED`, `APP_NOT_AUTHORISED`, `APP_REVOKED`, `APP_ORIGIN_MISMATCH`, \
                        `APP_SECRET_INVALID` or `APP_IDENTITY_REQUIRED`.

                        ## What is promised

                        Each operation carries `x-horecaos-surface`. **`published`** operations are the \
                        guaranteed public surface: a published path is never removed within v1, and change is \
                        additive only. **`internal`** operations are channel-specific (the Telegram and dine-in \
                        QR routes) and may change without notice; do not build on them.

                        ## Deprecation policy

                        A breaking change to a published path is a new major version served alongside the old \
                        one. The old version keeps working for at least **%3$d months** from the day the \
                        replacement is announced; an operation scheduled for removal is marked `deprecated` in \
                        this document from that day. Nothing in the published surface is removed inside `v1`.
                        """.formatted(
                        StorefrontAppHeaders.APP_ID,
                        StorefrontAppHeaders.APP_SECRET,
                        StorefrontContractPolicy.DEPRECATION_WINDOW_MONTHS));

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("deprecationWindowMonths", StorefrontContractPolicy.DEPRECATION_WINDOW_MONTHS);
        policy.put("publishedPathsNeverRemovedWithin", "v1");
        policy.put("appIdHeader", StorefrontAppHeaders.APP_ID);
        policy.put("appSecretHeader", StorefrontAppHeaders.APP_SECRET);
        openApi.getInfo().addExtension("x-horecaos-contract", policy);

        if (openApi.getComponents() != null) {
            openApi.getComponents()
                    .addSecuritySchemes(
                            APP_ID_SCHEME,
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.APIKEY)
                                    .in(SecurityScheme.In.HEADER)
                                    .name(StorefrontAppHeaders.APP_ID)
                                    .description("The registered app's id. An identifier, not a secret: it "
                                            + "appears in a browser bundle by design. Sent alongside the "
                                            + "customer's session, never instead of it."));
            openApi.getComponents()
                    .addSecuritySchemes(
                            APP_SECRET_SCHEME,
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.APIKEY)
                                    .in(SecurityScheme.In.HEADER)
                                    .name(StorefrontAppHeaders.APP_SECRET)
                                    .description("A confidential client's secret. Server-side only; a public "
                                            + "client has none and must not send one."));
        }

        Paths paths = openApi.getPaths();
        if (paths == null) {
            return;
        }
        paths.forEach((path, item) -> {
            String surface = StorefrontPublishedSurface.isPublished(path)
                    ? StorefrontContractPolicy.SURFACE_PUBLISHED
                    : StorefrontContractPolicy.SURFACE_INTERNAL;
            for (Operation operation : item.readOperations()) {
                operation.addExtension(StorefrontContractPolicy.SURFACE_EXTENSION, surface);
            }
        });
    }
}
