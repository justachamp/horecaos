package uz.horecaos.platform.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.TenantImageCheck.Verdict;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;

/**
 * What a feedback tag's icon must be (ADR 0159, Decision 2): a tenant-owned, public, available raster image of
 * the ratio asked, no larger than the limit -- and the reason it is not, as a verdict the console can show.
 *
 * <p>Each condition has its own test with every other condition satisfied, so a check that quietly stopped
 * looking at one of them would fail exactly one test, and the one that names it.
 */
class TenantImageCheckAdapterTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-8000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-8000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9f10-8000-7000-8000-0000000000b1");
    private static final UUID ASSET = UUID.fromString("018f9f10-8000-7000-8000-0000000000d1");
    private static final long ONE_MEGABYTE = 1_048_576L;

    private MediaAssetService assets;
    private TenantImageCheckAdapter adapter;

    @BeforeEach
    void setUp() {
        assets = mock(MediaAssetService.class);
        adapter = new TenantImageCheckAdapter(assets);
    }

    private void stored(MediaAsset asset) {
        when(assets.find(any(), any())).thenReturn(Optional.of(asset));
    }

    private static MediaAsset asset(
            MediaOwner owner,
            MediaVisibility visibility,
            MediaAssetStatus status,
            @Nullable String contentType,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Long size) {
        return new MediaAsset(
                new MediaAssetId(ASSET),
                TENANT,
                owner,
                "tenant/key.jpg",
                "bucket",
                status,
                visibility,
                "image/jpeg",
                size == null ? 0 : size,
                null,
                contentType,
                size,
                null,
                "icon.jpg",
                width,
                height,
                null,
                null,
                Instant.parse("2026-10-08T10:00:00Z"));
    }

    /** A good icon: tenant-owned, public, available, a JPEG at exactly 3:2 and a little under a megabyte. */
    private static MediaAsset good() {
        return asset(
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "image/jpeg",
                600,
                400,
                200_000L);
    }

    private Verdict check() {
        return adapter.check(TENANT, ASSET, 3, 2, ONE_MEGABYTE);
    }

    @Test
    @DisplayName("a tenant-owned, public, available 3:2 raster image under the limit is accepted")
    void aGoodIconIsAccepted() {
        stored(good());

        assertThat(check()).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    @DisplayName("no such asset, or none of this tenant's, is one answer")
    void anUnknownAssetIsUnknown() {
        when(assets.find(any(), any())).thenReturn(Optional.empty());

        assertThat(check()).isEqualTo(Verdict.UNKNOWN_ASSET);
    }

    @Test
    @DisplayName("an asset a brand or a location owns is not the tenant's to use as an icon")
    void aBrandOwnedAssetIsNotTenantOwned() {
        stored(asset(
                MediaOwner.brand(BRAND),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "image/jpeg",
                600,
                400,
                200_000L));
        assertThat(check()).isEqualTo(Verdict.NOT_TENANT_OWNED);

        stored(asset(
                MediaOwner.tenant(OTHER_TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "image/jpeg",
                600,
                400,
                200_000L));
        assertThat(check()).as("owned by a different tenant").isEqualTo(Verdict.NOT_TENANT_OWNED);
    }

    @Test
    @DisplayName("a private asset would never be served to a customer, so it is refused")
    void aPrivateAssetIsRefused() {
        stored(asset(
                MediaOwner.tenant(TENANT),
                MediaVisibility.PRIVATE,
                MediaAssetStatus.AVAILABLE,
                "image/jpeg",
                600,
                400,
                200_000L));

        assertThat(check()).isEqualTo(Verdict.NOT_PUBLIC);
    }

    @Test
    @DisplayName("an asset not yet verified, rejected or deleted is not available")
    void anUnavailableAssetIsRefused() {
        for (MediaAssetStatus status : MediaAssetStatus.values()) {
            if (status == MediaAssetStatus.AVAILABLE) {
                continue;
            }
            stored(asset(MediaOwner.tenant(TENANT), MediaVisibility.PUBLIC, status, "image/jpeg", 600, 400, 200_000L));

            assertThat(check()).as(status.name()).isEqualTo(Verdict.NOT_AVAILABLE);
        }
    }

    @Test
    @DisplayName(
            "a video, a vector image, a document and an image whose size the pipeline never read are not raster images")
    void onlyARasterImageWithKnownDimensionsPasses() {
        for (String type : new String[] {"video/mp4", "image/svg+xml", "application/pdf"}) {
            stored(asset(
                    MediaOwner.tenant(TENANT),
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.AVAILABLE,
                    type,
                    600,
                    400,
                    200_000L));
            assertThat(check()).as(type).isEqualTo(Verdict.NOT_A_RASTER_IMAGE);
        }
        stored(asset(
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "image/png",
                null,
                null,
                200_000L));
        assertThat(check()).as("dimensions never read").isEqualTo(Verdict.NOT_A_RASTER_IMAGE);
        stored(asset(
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                null,
                600,
                400,
                200_000L));
        assertThat(check()).as("content type never verified").isEqualTo(Verdict.NOT_A_RASTER_IMAGE);
    }

    @Test
    @DisplayName("3:2 is checked within one percent: a pixel off still passes, a 4:3 and a square do not")
    void theRatioHasAOnePercentTolerance() {
        int[][] passes = {{300, 200}, {301, 200}, {299, 200}, {1500, 1000}, {900, 600}};
        for (int[] size : passes) {
            stored(asset(
                    MediaOwner.tenant(TENANT),
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.AVAILABLE,
                    "image/webp",
                    size[0],
                    size[1],
                    10_000L));
            assertThat(check()).as("%dx%d", size[0], size[1]).isEqualTo(Verdict.ACCEPTED);
        }
        int[][] fails = {{400, 300}, {400, 400}, {200, 300}, {310, 200}, {1600, 1000}};
        for (int[] size : fails) {
            stored(asset(
                    MediaOwner.tenant(TENANT),
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.AVAILABLE,
                    "image/webp",
                    size[0],
                    size[1],
                    10_000L));
            assertThat(check()).as("%dx%d", size[0], size[1]).isEqualTo(Verdict.WRONG_ASPECT_RATIO);
        }
    }

    @Test
    @DisplayName("the limit is the store's verified size, inclusive, and an unverified size is too large to trust")
    void theSizeIsTheVerifiedSize() {
        stored(asset(
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "image/jpeg",
                600,
                400,
                ONE_MEGABYTE));
        assertThat(check()).as("exactly a megabyte").isEqualTo(Verdict.ACCEPTED);

        stored(asset(
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "image/jpeg",
                600,
                400,
                ONE_MEGABYTE + 1));
        assertThat(check()).isEqualTo(Verdict.TOO_LARGE);

        stored(asset(
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "image/jpeg",
                600,
                400,
                null));
        assertThat(check()).as("no verified size").isEqualTo(Verdict.TOO_LARGE);
    }
}
