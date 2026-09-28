package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuVariant;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.StorefrontMenu;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The public menu (ADR 0016).
 *
 * <p>Unauthenticated by design: this is the menu a customer browses before they
 * have an account. It serves only the immutable publication, so there is no path
 * from this endpoint to a draft.
 */
@RestController
@RequestMapping("/api/v1/storefront")
@Tag(name = "Storefront catalog", description = "The published menu a customer sees")
public class StorefrontCatalogController {

    private final StorefrontCatalogQuery storefront;

    public StorefrontCatalogController(StorefrontCatalogQuery storefront) {
        this.storefront = storefront;
    }

    @GetMapping("/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/menu")
    @Operation(
            summary = "The live menu for one location",
            description = "Reads the active publication and applies the location's current "
                    + "availability on top. A variant the location does not offer is absent; "
                    + "one it has run out of is present and not orderable. The channel is "
                    + "required and supplies both the publication and the price plane (ADR "
                    + "0036): a menu priced against another channel is a menu whose prices "
                    + "change at checkout.")
    public ResponseEntity<StorefrontCatalogQuery.StorefrontMenu> menu(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(defaultValue = "uz") String locale,
            @RequestParam String channel) {

        return storefront
                .menuFor(tenantId, brandId, locationId, locale, channel)
                .map(menu -> ResponseEntity.ok()
                        // Rows 4.4c/4.4d, ADR 0033: the publication id alone
                        // used to be a sound ETag, back when this response
                        // was a pure function of the immutable publication.
                        // Now that inventory's live orderable/remainingQuantity
                        // is folded in (menuFor -> withAvailability), a stop or
                        // a restock changes the body without changing the
                        // publication, so the ETag must change too or a
                        // conditional revalidation would wrongly 304 a client
                        // straight back to what it already has.
                        .eTag("\"%s:%s\"".formatted(menu.publicationId(), availabilityFingerprint(menu)))
                        // `noCache`, not `maxAge`: a shared/public cache may
                        // still store this response, but HTTP requires it to
                        // revalidate with the origin (conditional GET, the
                        // ETag above) before ever serving it again, rather
                        // than replaying up to `maxAge`'s window blind. A stop
                        // taken one second after a cache last revalidated
                        // must be visible on the very next request, not up to
                        // 30 seconds later.
                        .cacheControl(CacheControl.noCache().cachePublic())
                        .body(menu))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This brand has no published menu"));
    }

    /**
     * A content digest of exactly the part of {@link StorefrontMenu} that can
     * change between two reads of the same publication: every variant's
     * {@code orderable}/{@code remainingQuantity}. Deterministic regardless of
     * the order {@code menuFor} happens to enumerate products and variants in,
     * so two reads with identical availability always produce the same
     * fingerprint and two reads that differ in even one variant never do.
     */
    private static String availabilityFingerprint(StorefrontMenu menu) {
        List<String> perVariant = menu.products().stream()
                .flatMap(product -> product.variants().stream())
                .map(StorefrontCatalogController::variantAvailabilityToken)
                .sorted()
                .toList();
        return sha256Hex(String.join("|", perVariant));
    }

    private static String variantAvailabilityToken(MenuVariant variant) {
        return "%s:%s:%s".formatted(variant.variantId(), variant.orderable(), variant.remainingQuantity());
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JDK distribution this platform runs on ships SHA-256
            // (it is a mandatory JCA provider per the java.security spec) --
            // this branch exists only so the method signature stays checked-
            // exception-free at the call site above.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
