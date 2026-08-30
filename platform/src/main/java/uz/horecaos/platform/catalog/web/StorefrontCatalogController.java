package uz.horecaos.platform.catalog.web;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Duration;

import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
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
    @Operation(summary = "The live menu for one location",
            description = "Reads the active publication and applies the location's current "
                    + "availability on top. A variant the location does not offer is absent; "
                    + "one it has run out of is present and not orderable. The channel is "
                    + "required and supplies both the publication and the price plane (ADR "
                    + "0036): a menu priced against another channel is a menu whose prices "
                    + "change at checkout.")
    public ResponseEntity<StorefrontCatalogQuery.StorefrontMenu> menu(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId,
            @RequestParam(defaultValue = "uz") String locale,
            @RequestParam String channel) {

        return storefront.menuFor(tenantId, brandId, locationId, locale, channel)
                .map(menu -> ResponseEntity.ok()
                        // The publication id is a content identity, so a menu can
                        // be cached briefly and still change the moment a
                        // location's availability does.
                        .eTag("\"%s\"".formatted(menu.publicationId()))
                        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                        .body(menu))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "This brand has no published menu"));
    }
}
