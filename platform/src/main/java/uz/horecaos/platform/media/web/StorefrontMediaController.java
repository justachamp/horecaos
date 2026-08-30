package uz.horecaos.platform.media.web;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.application.MediaAssetService;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Pictures on the published menu (ADR 0010, ADR 0016).
 *
 * <p>The menu carries a URL to this endpoint for each of a product's media
 * assets, and this redirects to a short-lived signed URL for the object itself.
 *
 * <h2>Why a redirect rather than a URL on the menu</h2>
 *
 * ADR 0010 rejects both of the simpler answers in as many words. A public bucket
 * with predictable keys is "enumerable across tenants, and no way to revoke
 * access to a single asset" — rated <em>never</em>. Persisting an environment's
 * public URL on a business row is rejected for the same reason: it would make
 * changing bucket, CDN or domain a data migration. The CDN origin that the ADR
 * does choose is listed there as not yet built.
 *
 * <p>So the menu holds a URL that names an asset and no environment, and the
 * bytes stay in a private bucket reached by a signature that expires. When the
 * CDN origin lands it can serve this same path, and no stored menu changes.
 *
 * <h2>Why it is unauthenticated, and what that costs</h2>
 *
 * The menu is public because a customer browses before they have an account, and
 * a picture behind a bearer token would be a menu of broken images for exactly
 * the people the storefront is trying to win. So this is anonymous — like the
 * menu, the pickup locations, and the delivery fee.
 *
 * <p>Two conditions keep that from being an enumeration hole. The asset must be
 * {@code PUBLIC}, which is the visibility the ADR defines as "servable through
 * the CDN origin" and which nothing sets by default; and it must be displayable,
 * so an unverified upload is never served. An asset id is a version-4 UUID, so
 * guessing one is not a practical attack, and being wrong is answered
 * identically to being unauthorised — not-found, with no way to tell whether the
 * asset exists, is private, or is still being scanned.
 *
 * <p><strong>This deliberately does not check that the asset is on a live
 * menu.</strong> Doing so would put a catalog dependency in the media module and
 * a publication read on every image request; what it would buy is stopping
 * somebody who already holds a PUBLIC asset id from seeing a picture that is
 * public. The visibility flag is the control, and it is the one an operator sets
 * deliberately.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/media")
@Tag(name = "Storefront media", description = "Pictures on the published menu")
public class StorefrontMediaController {

    /**
     * How long a browser may reuse this redirect.
     *
     * Shorter than the signature the redirect hands out, so a cached redirect
     * never outlives the URL behind it. A menu is cached for thirty seconds
     * (ADR 0016) and a picture changes far less often than a price does.
     */
    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final MediaAssetService media;

    public StorefrontMediaController(MediaAssetService media) {
        this.media = media;
    }

    @GetMapping("/{assetId}")
    @Operation(summary = "Redirect to a published image",
            description = "302 to a short-lived signed URL. Only a PUBLIC, displayable asset "
                    + "resolves; anything else is not found, and private, missing and "
                    + "unverified are one answer so an id cannot be probed.")
    public ResponseEntity<Void> image(@PathVariable UUID tenantId, @PathVariable UUID assetId) {
        MediaAssetId id = new MediaAssetId(assetId);

        boolean servable = media.find(tenantId, id)
                .filter(asset -> asset.visibility() == MediaVisibility.PUBLIC)
                .filter(asset -> asset.status().isDisplayable())
                .isPresent();

        // One answer for "no such asset", "not yours", "private" and "not
        // verified yet". Telling them apart is how an asset id becomes something
        // to enumerate with.
        URI target = servable
                ? media.downloadUrl(tenantId, id).orElseThrow(StorefrontMediaController::notFound)
                : notFoundThrow();

        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .location(target)
                .build();
    }

    private static URI notFoundThrow() {
        throw notFound();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such image");
    }
}
