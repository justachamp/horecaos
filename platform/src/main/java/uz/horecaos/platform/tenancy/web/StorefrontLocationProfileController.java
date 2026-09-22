package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.application.StorefrontLocationProfileQuery;
import uz.horecaos.platform.tenancy.application.StorefrontLocationProfileQuery.LocationProfile;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * "What is this branch called, and where is it" (ADR 0016).
 *
 * <p>Public and unauthenticated, like the menu and pickup-location search it
 * sits beside: a customer choosing where to collect an order, or reading their
 * own past pickup order, must not need an account to see the branch's own
 * published name and address. Every field here is one {@code tenant.locations}
 * already treats as published — see {@link
 * uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontLocationProfileStore}'s
 * own doc — so this adds no new disclosure, only a read for facts the pickup
 * search and the receipt already carry separately.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(name = "Storefront location profile", description = "A branch's own published name and address")
public class StorefrontLocationProfileController {

    private final StorefrontLocationProfileQuery profiles;

    public StorefrontLocationProfileController(StorefrontLocationProfileQuery profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    @Operation(
            summary = "A branch's own published display name and address",
            description = "Only fields the tenant already publishes on this branch elsewhere -- "
                    + "the same name and address shown in pickup search and printed on a "
                    + "receipt. Not found for an unknown, inactive, or cross-tenant location id.")
    public ResponseEntity<LocationProfile> profile(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        LocationProfile profile = profiles.profile(tenantId, brandId, locationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such location"));
        return cached(profile);
    }

    private ResponseEntity<LocationProfile> cached(LocationProfile body) {
        // A branch's own published address changes rarely; matches the same
        // ADR 0033 ceiling the other browse reads beside it use.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(body);
    }
}
