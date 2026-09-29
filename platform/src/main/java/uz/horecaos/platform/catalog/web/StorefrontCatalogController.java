package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
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

    /** Serialises with map entries in key order, so two equal menus always digest alike. */
    private static final JsonMapper DIGEST_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

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
                        // Rows 4.4c/4.4d, ADR 0033, row 10.12: the publication id alone
                        // used to be a sound ETag, back when this response
                        // was a pure function of the immutable publication.
                        // It no longer is. Inventory's live orderable/remainingQuantity,
                        // the live prices, the brand's default language (which name a
                        // customer whose own language has none is shown) and the live
                        // preset wording are all folded in by menuFor, and none of them
                        // moves the publication. The ETag therefore digests the body the
                        // customer would receive: a conditional revalidation is answered
                        // 304 only when there is nothing new to say.
                        .eTag("\"%s:%s\"".formatted(menu.publicationId(), contentDigest(menu)))
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
     * A digest of the whole {@link StorefrontMenu} body, not of a hand-picked part of it.
     *
     * <p>This used to fingerprint only every variant's {@code orderable}/{@code remainingQuantity},
     * on the premise that nothing else could change between two reads of one publication. Each
     * later live input (a price, the brand's default language, a preset's wording) made that
     * premise false without anyone touching the fingerprint, and the result was a browser
     * revalidating to a 304 for a body that had changed. Digesting the body itself has no such
     * list to forget to extend: the ETag moves exactly when what the customer is sent does.
     * {@code menuFor} runs on every request either way, so this adds one serialisation, not a read.
     */
    private static String contentDigest(StorefrontMenu menu) {
        return sha256Hex(DIGEST_MAPPER.writeValueAsString(menu));
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
