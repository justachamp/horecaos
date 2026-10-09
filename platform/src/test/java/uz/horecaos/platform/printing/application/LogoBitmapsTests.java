package uz.horecaos.platform.printing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import uz.horecaos.platform.media.api.MediaAssetContent;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.printing.render.Bitmap1;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * The logo accelerator (ADR 0154, Decision 3; ADR 0033): keyed by tenant, asset and roll width, so a 58 mm
 * roll never gets the 80 mm bitmap and one tenant never gets another's; only a logo that came out is kept.
 */
class LogoBitmapsTests {

    private static final UUID TENANT = UUID.fromString("018fc154-1000-7000-8000-0000000000a1");
    private static final UUID ASSET = UUID.fromString("018fc154-1000-7000-8000-0000000000aa");

    /** Counts reads, so a cache hit is observable as a read that did not happen. */
    private static final class CountingMedia implements MediaAssetContent {
        final AtomicInteger reads = new AtomicInteger();
        private final byte @Nullable [] bytes;

        CountingMedia(byte @Nullable [] bytes) {
            this.bytes = bytes;
        }

        @Override
        public Optional<byte[]> original(UUID tenantId, MediaAssetId assetId, int maximumBytes) {
            reads.incrementAndGet();
            return Optional.ofNullable(bytes);
        }
    }

    private static byte[] square() {
        BufferedImage image = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 400, 100);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(100, 25, 200, 50);
        graphics.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static ConcurrentMapCacheManager caches() {
        return new ConcurrentMapCacheManager(CacheRegistry.PRINTING_LOGO_BITMAPS.cacheName());
    }

    @Test
    @DisplayName("a logo is read once per roll width and the second print is a cache hit")
    void aLogoIsCachedPerWidth() {
        CountingMedia media = new CountingMedia(square());
        LogoBitmaps logos = new LogoBitmaps(media, caches());

        Optional<Bitmap1> first = logos.forAsset(TENANT, ASSET.toString(), 576);
        Optional<Bitmap1> second = logos.forAsset(TENANT, ASSET.toString(), 576);
        Optional<Bitmap1> narrow = logos.forAsset(TENANT, ASSET.toString(), 384);

        assertThat(first).isPresent().isEqualTo(second);
        assertThat(media.reads).as("two widths, one read each").hasValue(2);
        assertThat(narrow.orElseThrow().width())
                .as("the 58 mm roll gets its own bitmap")
                .isLessThan(first.orElseThrow().width());
    }

    @Test
    @DisplayName("another tenant asking for the same asset id does not get the first tenant's bitmap")
    void theKeyCarriesTheTenant() {
        CountingMedia media = new CountingMedia(square());
        LogoBitmaps logos = new LogoBitmaps(media, caches());
        UUID other = UUID.fromString("018fc154-2000-7000-8000-0000000000a1");

        logos.forAsset(TENANT, ASSET.toString(), 576);
        logos.forAsset(other, ASSET.toString(), 576);

        assertThat(media.reads)
                .as("each tenant's read goes to the media module, which checks ownership")
                .hasValue(2);
    }

    @Test
    @DisplayName("a failure is not cached: the next print tries again")
    void aFailureIsRetried() {
        CountingMedia media = new CountingMedia(null);
        LogoBitmaps logos = new LogoBitmaps(media, caches());

        assertThat(logos.forAsset(TENANT, ASSET.toString(), 576)).isEmpty();
        assertThat(logos.forAsset(TENANT, ASSET.toString(), 576)).isEmpty();

        assertThat(media.reads).hasValue(2);
    }

    @Test
    @DisplayName("something that is not an asset id is no logo, and nothing is read")
    void aBadIdIsNoLogo() {
        CountingMedia media = new CountingMedia(square());
        LogoBitmaps logos = new LogoBitmaps(media, caches());

        assertThat(logos.forAsset(TENANT, "../../etc/passwd", 576)).isEmpty();

        assertThat(media.reads).hasValue(0);
    }

    @Test
    @DisplayName("without a registered cache the logo still prints, just without the saving")
    void noCacheStillWorks() {
        CountingMedia media = new CountingMedia(square());
        LogoBitmaps logos = new LogoBitmaps(media, new ConcurrentMapCacheManager() {
            @Override
            public org.springframework.cache.@Nullable Cache getCache(String name) {
                return null;
            }
        });

        assertThat(logos.forAsset(TENANT, ASSET.toString(), 576)).isPresent();
        assertThat(logos.forAsset(TENANT, ASSET.toString(), 576)).isPresent();

        assertThat(media.reads).hasValue(2);
    }
}
