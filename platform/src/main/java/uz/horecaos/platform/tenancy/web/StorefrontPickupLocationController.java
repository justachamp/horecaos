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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.application.StorefrontPickupLocationQuery;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The pre-account pickup branch chooser.
 *
 * <p>It is public for the same reason a menu is public: choosing where to
 * browse must not demand a customer account. Coordinates are used only for
 * this calculation; the response exposes branch addresses and distances, not
 * the caller's point.
 */
@RestController
@RequestMapping("/api/v1/storefront")
@Tag(name = "Storefront pickup locations", description = "Pickup branches near a customer")
public class StorefrontPickupLocationController {

    private final StorefrontPickupLocationQuery locations;

    public StorefrontPickupLocationController(StorefrontPickupLocationQuery locations) {
        this.locations = locations;
    }

    @GetMapping("/pickup-locations")
    @Operation(
            summary = "Find the nearest pickup locations with a published storefront menu",
            description = "Returns at most twenty active pickup branches, nearest first. "
                    + "Each result carries the same current serviceability answer the checkout "
                    + "path will later re-resolve authoritatively.")
    public ResponseEntity<StorefrontPickupLocationQuery.PickupLocations> nearbyPickupLocations(
            @RequestParam double lat, @RequestParam double lon, @RequestParam(defaultValue = "10") int limit) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(locations.nearby(new GeoPoint(lat, lon), limit));
    }

    /**
     * A branch's public name and address, by id (2026-09-21 audit follow-up
     * (d)). Built for a customer's own order detail: {@code
     * StorefrontOrderingController.OrderResponse} names the {@code locationId}
     * a pickup order was placed at, and this is what turns that id into
     * something to show — "Central kitchen, 1 Demo Street" rather than a UUID.
     *
     * <p>Unauthenticated for the same reason {@link #nearbyPickupLocations} is:
     * a customer reading a receipt has already left the account boundary that
     * matters, since the order id itself was never a secret to begin with, and
     * a branch's name and address are not personal data. Carries no
     * coordinate, unlike the search above — a caller who already has a
     * location id has no use for its point, and ADR 0029 keeps it out of a
     * response nobody asked for it in.
     */
    @GetMapping("/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/profile")
    @Operation(
            summary = "A branch's public name and address",
            description = "404 when the tenant, brand or location does not exist or is not "
                    + "active. Carries no coordinate.")
    public ResponseEntity<LocationProfileView> profile(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return locations
                .profile(tenantId, brandId, locationId)
                .map(profile -> ResponseEntity.ok()
                        .cacheControl(
                                CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                        .body(LocationProfileView.of(profile)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such location"));
    }

    /** The wire shape for {@link #profile}. */
    public record LocationProfileView(
            UUID locationId, String brandName, String locationName, String addressLine, String district, String city) {

        static LocationProfileView of(StorefrontPickupLocationQuery.LocationProfile profile) {
            return new LocationProfileView(
                    profile.locationId(),
                    profile.brandName(),
                    profile.locationName(),
                    profile.addressLine(),
                    profile.district(),
                    profile.city());
        }
    }
}
