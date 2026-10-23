package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 *
 * <p>{@code POST} carrying the point in the body, not {@code GET} with it in
 * the query string. A customer's live position is personal data, and a query
 * string is logged by every proxy and access log between here and the edge —
 * exactly the same reasoning that keeps a saved address's coordinate out of
 * every URL this platform serves. There is no write here and nothing is
 * created: {@code EndpointCapabilityDeclarationTests} exempts this path by
 * name for that reason, the same way it exempts the other unauthenticated
 * pre-account reads that cannot hold a capability.
 */
@RestController
@RequestMapping("/api/v1/storefront")
@Tag(name = "Storefront pickup locations", description = "Pickup branches near a customer")
public class StorefrontPickupLocationController {

    private final StorefrontPickupLocationQuery locations;

    public StorefrontPickupLocationController(StorefrontPickupLocationQuery locations) {
        this.locations = locations;
    }

    @PostMapping("/pickup-locations")
    @Operation(
            summary = "Find the nearest pickup locations with a published storefront menu",
            description = "Returns at most twenty active pickup branches, nearest first; with no point, in the "
                    + "order the brand set for its storefront (distanceKnown is then false on every branch). Name "
                    + "brandId to search one brand, as a brand's own storefront does; without it the search spans every "
                    + "brand and, with no point, lists each brand's own order interleaved by rank. Each "
                    + "result carries the same current serviceability answer the checkout path "
                    + "will later re-resolve authoritatively. POST so the caller's coordinate "
                    + "travels in the body rather than the query string; nothing is written and "
                    + "no resource is created.")
    public ResponseEntity<StorefrontPickupLocationQuery.PickupLocations> nearbyPickupLocations(
            @Valid @RequestBody PickupLocationSearchRequest body) {

        int limit = body.limit() == null ? 10 : body.limit();
        return ResponseEntity.ok(locations.nearby(body.point(), body.brandId(), limit));
    }

    /**
     * @param point the caller's own position, WGS 84 degrees; absent when the customer has shared none, and the branches
     *              then come in the order the brand set for its storefront (ADR 0183 D17)
     * @param limit at most this many branches; defaults to 10 and is capped by
     *              {@link StorefrontPickupLocationQuery#MAXIMUM_LIMIT}
     * @param brandId the one brand to search; absent for a search across every brand with a published storefront menu.
     *                A position is only meaningful inside the brand that set it, so the brand's own order is the one a
     *                storefront gets by naming itself
     */
    public record PickupLocationSearchRequest(
            @Nullable @Valid GeoPoint point,
            @Nullable Integer limit,
            @Nullable UUID brandId) {}

    // A branch's public name and address by id (2026-09-21 audit follow-up
    // (d), for a pickup order's own detail) is served by the dedicated
    // `StorefrontLocationProfileController` at this same path
    // (`GET .../locations/{locationId}/profile`, built in an earlier wave
    // for exactly this and the pickup-confirmation screen alike) rather than
    // a second implementation here — batch 8 integration found both an
    // already-merged `StorefrontLocationProfileController` and this wave's
    // own from-scratch route mapped to the identical path; the pre-existing
    // one is kept as the single source of truth (see its own doc comment,
    // and `StorefrontLocationProfileQuery.LocationProfile` for the wire
    // shape the storefront's `LocationProfileService` actually consumes).
}
