package uz.horecaos.platform.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetIngestion;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;

/**
 * ADR 0139 over ADR 0010: what may become a person's photo.
 *
 * <p>{@code MediaAvailability#allDisplayable} answers "displayable in this
 * tenant", which a public catalogue image and a brand-owned banner both are and
 * neither may be as somebody's picture. The adapter asks the stricter question, and
 * answers the same "no" for an asset of another tenant as for one that does not
 * exist, so the photo endpoint is no existence oracle for asset ids (V0069).
 */
class StaffPhotoAdapterTests {

    private static final UUID TENANT = UUID.fromString("018fb500-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb500-4000-7000-8000-0000000000a2");

    private MediaAssetService assets;
    private MediaAssetIngestion ingestion;
    private StaffPhotoAdapter adapter;

    @BeforeEach
    void setUp() {
        assets = mock(MediaAssetService.class);
        ingestion = mock(MediaAssetIngestion.class);
        adapter = new StaffPhotoAdapter(ingestion, assets);
    }

    private MediaAsset asset(
            UUID tenant, MediaOwner owner, MediaVisibility visibility, MediaAssetStatus status, MediaAssetId id) {
        return new MediaAsset(
                id,
                tenant,
                owner,
                tenant + "/tenant/" + id,
                "horecaos-media",
                status,
                visibility,
                "image/jpeg",
                10,
                null,
                "image/jpeg",
                10L,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-10-01T00:00:00Z"));
    }

    private MediaAssetId given(MediaOwner owner, MediaVisibility visibility, MediaAssetStatus status) {
        MediaAssetId id = MediaAssetId.generate();
        when(assets.find(TENANT, id)).thenReturn(Optional.of(asset(TENANT, owner, visibility, status, id)));
        return id;
    }

    @Test
    @DisplayName("a verified, private asset the tenant itself owns is a valid photo")
    void aPrivateTenantOwnedAssetIsAccepted() {
        MediaAssetId id = given(MediaOwner.tenant(TENANT), MediaVisibility.PRIVATE, MediaAssetStatus.AVAILABLE);

        assertThat(adapter.isPrivateTenantAsset(TENANT, id.value())).isTrue();
    }

    @Test
    @DisplayName("a public asset, a brand's or location's asset and a tenant-owned asset of another tenant id are not")
    void everyOtherShapeIsRefused() {
        MediaAssetId publicOne = given(MediaOwner.tenant(TENANT), MediaVisibility.PUBLIC, MediaAssetStatus.AVAILABLE);
        MediaAssetId brandOwned =
                given(MediaOwner.brand(UUID.randomUUID()), MediaVisibility.PRIVATE, MediaAssetStatus.AVAILABLE);
        MediaAssetId locationOwned =
                given(MediaOwner.location(UUID.randomUUID()), MediaVisibility.PRIVATE, MediaAssetStatus.AVAILABLE);
        MediaAssetId ownedBySomeoneElse =
                given(MediaOwner.tenant(OTHER_TENANT), MediaVisibility.PRIVATE, MediaAssetStatus.AVAILABLE);

        for (MediaAssetId id : new MediaAssetId[] {publicOne, brandOwned, locationOwned, ownedBySomeoneElse}) {
            assertThat(adapter.isPrivateTenantAsset(TENANT, id.value())).isFalse();
        }
    }

    @Test
    @DisplayName("an asset not yet verified, or rejected, is not a photo")
    void anUnverifiedAssetIsRefused() {
        for (MediaAssetStatus status : new MediaAssetStatus[] {
            MediaAssetStatus.PENDING_UPLOAD,
            MediaAssetStatus.UPLOADED,
            MediaAssetStatus.REJECTED,
            MediaAssetStatus.DELETED
        }) {
            MediaAssetId id = given(MediaOwner.tenant(TENANT), MediaVisibility.PRIVATE, status);
            assertThat(adapter.isPrivateTenantAsset(TENANT, id.value()))
                    .as(status.name())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("an asset of another tenant and one that does not exist get the same answer")
    void foreignAndMissingAreIndistinguishable() {
        MediaAssetId foreign = MediaAssetId.generate();
        MediaAssetId missing = MediaAssetId.generate();
        // The media store finds an asset only inside the tenant it is asked about.
        when(assets.find(TENANT, foreign)).thenReturn(Optional.empty());
        when(assets.find(TENANT, missing)).thenReturn(Optional.empty());

        assertThat(adapter.isPrivateTenantAsset(TENANT, foreign.value()))
                .isEqualTo(adapter.isPrivateTenantAsset(TENANT, missing.value()))
                .isFalse();
        assertThat(adapter.signedReadUrl(TENANT, foreign.value())).isEmpty();
    }

    @Test
    @DisplayName("a signed link is issued only for an asset that passed the strict check")
    void aLinkIsIssuedOnlyForAValidPhoto() {
        MediaAssetId good = given(MediaOwner.tenant(TENANT), MediaVisibility.PRIVATE, MediaAssetStatus.AVAILABLE);
        MediaAssetId publicOne = given(MediaOwner.tenant(TENANT), MediaVisibility.PUBLIC, MediaAssetStatus.AVAILABLE);
        when(assets.downloadUrl(TENANT, good)).thenReturn(Optional.of(URI.create("https://signed.example/good")));

        assertThat(adapter.signedReadUrl(TENANT, good.value())).contains(URI.create("https://signed.example/good"));
        assertThat(adapter.signedReadUrl(TENANT, publicOne.value())).isEmpty();
        verify(assets, never()).downloadUrl(TENANT, publicOne);
    }

    @Test
    @DisplayName("ingest creates a private, tenant-owned asset and reports the pipeline's rejection code")
    void ingestIsPrivateAndTenantOwned() {
        MediaAssetId id = MediaAssetId.generate();
        byte[] accepted = {1};
        byte[] refused = {2};
        when(ingestion.ingestFromBytes(
                        TENANT, MediaAssetIngestion.OwnerScope.TENANT, TENANT, false, accepted, "me.png", null))
                .thenReturn(MediaAssetIngestion.IngestOutcome.accepted(id));
        when(ingestion.ingestFromBytes(
                        TENANT, MediaAssetIngestion.OwnerScope.TENANT, TENANT, false, refused, null, null))
                .thenReturn(MediaAssetIngestion.IngestOutcome.rejected("CONTENT_NOT_AN_IMAGE"));

        var ok = adapter.ingest(TENANT, accepted, "me.png", null);
        var rejected = adapter.ingest(TENANT, refused, null, null);

        assertThat(ok.accepted()).isTrue();
        assertThat(ok.assetId()).isEqualTo(id.value());
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo("CONTENT_NOT_AN_IMAGE");
    }
}
