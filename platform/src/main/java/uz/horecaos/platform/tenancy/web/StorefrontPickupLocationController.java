package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.application.StorefrontPickupLocationQuery;

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
}
