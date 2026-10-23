package uz.horecaos.platform.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.MediaPurpose;
import uz.horecaos.platform.media.api.PurposedImages.Verdict;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;

/**
 * The attach check for a KIOSK_IDLE picture (ADR 0187, D25): one reason per failing condition, and one answer
 * ({@code UNKNOWN_ASSET}) for everything that would let an asset id be probed.
 */
class PurposedImagesAdapterTests {

    private static final UUID TENANT = UUID.fromString("018fd187-1000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fd187-1000-7000-8000-0000000000a2");
    private static final UUID ASSET = UUID.fromString("018fd187-1000-7000-8000-0000000000e1");
    private static final long ONE_MEGABYTE = 1_048_576L;

    private MediaAssetService assets;
    private PurposedImagesAdapter adapter;

    @BeforeEach
    void setUp() {
        assets = mock(MediaAssetService.class);
        adapter = new PurposedImagesAdapter(assets);
    }

    private void stored(MediaAsset asset) {
        when(assets.find(any(), any())).thenReturn(Optional.of(asset));
    }

    private static MediaAsset asset(
            UUID tenant,
            MediaOwner owner,
            MediaVisibility visibility,
            MediaAssetStatus status,
            @Nullable String purpose,
            @Nullable String contentType,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Long size) {
        return new MediaAsset(
                new MediaAssetId(ASSET),
                tenant,
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
                "idle.jpg",
                width,
                height,
                null,
                null,
                Instant.parse("2026-10-14T10:00:00Z"),
                null,
                null,
                purpose);
    }

    /** A good picture: tenant-owned, public, available, KIOSK_IDLE, a JPEG at exactly 9:16 and well under a megabyte. */
    private static MediaAsset good() {
        return asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                540,
                960,
                200_000L);
    }

    private Verdict check() {
        return adapter.check(TENANT, ASSET, MediaPurpose.KIOSK_IDLE);
    }

    @Test
    @DisplayName("a verified 9:16 picture of this purpose under the cap is accepted")
    void aGoodPictureIsAccepted() {
        stored(good());
        assertThat(check()).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    @DisplayName("an asset uploaded for something else, and one that does not exist, are the same answer")
    void anAssetOfAnotherPurposeIsUnknown() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                null,
                "image/jpeg",
                540,
                960,
                200_000L));
        assertThat(check()).as("a catalogue photograph at exactly 9:16").isEqualTo(Verdict.UNKNOWN_ASSET);

        when(assets.find(any(), any())).thenReturn(Optional.empty());
        assertThat(check()).as("no such asset").isEqualTo(Verdict.UNKNOWN_ASSET);
    }

    @Test
    @DisplayName("a private asset and a brand-owned one are unknown, even carrying the purpose")
    void aPrivateOrBrandOwnedAssetIsUnknown() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PRIVATE,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                540,
                960,
                200_000L));
        assertThat(check()).isEqualTo(Verdict.UNKNOWN_ASSET);
        stored(asset(
                TENANT,
                MediaOwner.brand(UUID.randomUUID()),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                540,
                960,
                200_000L));
        assertThat(check()).isEqualTo(Verdict.UNKNOWN_ASSET);
    }

    @Test
    @DisplayName("an asset owned by another tenant's id is unknown")
    void anotherTenantsOwnerIsUnknown() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(OTHER_TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                540,
                960,
                200_000L));
        assertThat(check()).isEqualTo(Verdict.UNKNOWN_ASSET);
    }

    @Test
    @DisplayName("an AVIF is a raster image the platform stores but not one a kiosk takes: JPEG, PNG and WebP only")
    void onlyTheTypesThePurposeNamesAreAccepted() {
        for (String type : new String[] {"image/jpeg", "image/png", "image/webp"}) {
            stored(asset(
                    TENANT,
                    MediaOwner.tenant(TENANT),
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.AVAILABLE,
                    "KIOSK_IDLE",
                    type,
                    540,
                    960,
                    200_000L));
            assertThat(check()).as(type).isEqualTo(Verdict.ACCEPTED);
        }
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/avif",
                540,
                960,
                200_000L));
        assertThat(check()).isEqualTo(Verdict.NOT_A_RASTER_IMAGE);
    }

    @Test
    @DisplayName("a picture that is not verified yet, a vector image and a wrong shape each say so")
    void eachFailingConditionNamesItself() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.UPLOADED,
                "KIOSK_IDLE",
                null,
                null,
                null,
                null));
        assertThat(check()).isEqualTo(Verdict.NOT_AVAILABLE);

        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/svg+xml",
                540,
                960,
                1_000L));
        assertThat(check()).isEqualTo(Verdict.NOT_A_RASTER_IMAGE);

        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                640,
                480,
                200_000L));
        assertThat(check()).isEqualTo(Verdict.WRONG_ASPECT_RATIO);

        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                540,
                960,
                ONE_MEGABYTE + 1));
        assertThat(check()).isEqualTo(Verdict.TOO_LARGE);
    }

    @Test
    @DisplayName(
            "exactly one megabyte is allowed and a crop a pixel off 9:16 is allowed; a landscape picture of the same area is not")
    void theLimitsAreInclusiveAndTolerant() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                541,
                960,
                ONE_MEGABYTE));
        assertThat(check()).isEqualTo(Verdict.ACCEPTED);

        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.AVAILABLE,
                "KIOSK_IDLE",
                "image/jpeg",
                960,
                540,
                ONE_MEGABYTE));
        assertThat(check()).isEqualTo(Verdict.WRONG_ASPECT_RATIO);
    }

    @Test
    @DisplayName(
            "an upload's state is the pipeline's, with the code a rejection carries, and empty for another purpose's asset")
    void aStateIsReportedForThePurposesOwnAssetsOnly() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.REJECTED,
                "KIOSK_IDLE",
                null,
                null,
                null,
                null));
        when(assets.rejectionCodeOf(TENANT, new MediaAssetId(ASSET))).thenReturn(Optional.of("PURPOSE_ASPECT_RATIO"));

        var rejected =
                adapter.uploadState(TENANT, ASSET, MediaPurpose.KIOSK_IDLE).orElseThrow();
        assertThat(rejected.status()).isEqualTo(MediaAssetStatus.REJECTED);
        assertThat(rejected.rejectionCode()).isEqualTo("PURPOSE_ASPECT_RATIO");

        stored(good());
        var available =
                adapter.uploadState(TENANT, ASSET, MediaPurpose.KIOSK_IDLE).orElseThrow();
        assertThat(available.status()).isEqualTo(MediaAssetStatus.AVAILABLE);
        assertThat(available.rejectionCode()).isNull();

        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.REJECTED,
                null,
                null,
                null,
                null,
                null));
        assertThat(adapter.uploadState(TENANT, ASSET, MediaPurpose.KIOSK_IDLE)).isEmpty();
    }

    @Test
    @DisplayName("finalizing an asset of another purpose answers empty and queues nothing")
    void finalizingAnotherPurposesAssetIsRefused() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.PENDING_UPLOAD,
                null,
                null,
                null,
                null,
                null));

        assertThat(adapter.finalizeUpload(TENANT, ASSET, MediaPurpose.KIOSK_IDLE))
                .isEmpty();
        verify(assets, never()).finalizeUpload(any(), any());
    }

    @Test
    @DisplayName("finalizing the tenant's own purposed asset delegates to the ordinary finalize")
    void finalizingTheTenantsOwnPurposedAssetDelegates() {
        stored(asset(
                TENANT,
                MediaOwner.tenant(TENANT),
                MediaVisibility.PUBLIC,
                MediaAssetStatus.PENDING_UPLOAD,
                "KIOSK_IDLE",
                null,
                null,
                null,
                null));
        when(assets.finalizeUpload(TENANT, new MediaAssetId(ASSET))).thenReturn(MediaAssetStatus.UPLOADED);

        assertThat(adapter.finalizeUpload(TENANT, ASSET, MediaPurpose.KIOSK_IDLE))
                .contains(MediaAssetStatus.UPLOADED);
    }

    @Test
    @DisplayName(
            "a request is allocated for the tenant, public, with the purpose, and nothing else is chosen by the caller")
    void aRequestIsAllocatedTenantOwnedAndPublic() {
        UUID id = UUID.randomUUID();
        when(assets.requestUpload(
                        any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any()))
                .thenReturn(new MediaAssetService.UploadTicket(
                        new MediaAssetId(id), URI.create("https://store.example/put"), Map.of(), Instant.EPOCH));

        var ticket = adapter.requestUpload(TENANT, MediaPurpose.KIOSK_IDLE, "image/jpeg", 1000, "idle.jpg", null);

        assertThat(ticket.assetId()).isEqualTo(id);
        verify(assets)
                .requestUpload(
                        TENANT,
                        MediaOwner.tenant(TENANT),
                        MediaVisibility.PUBLIC,
                        "image/jpeg",
                        1000,
                        "idle.jpg",
                        null,
                        null,
                        MediaPurpose.KIOSK_IDLE);
    }
}
