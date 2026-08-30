package uz.horecaos.platform.media.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The only thing a business module ever holds for an image (ADR 0010).
 *
 * <p>Deliberately not a path, a URL, or a bucket key. The legacy system stored
 * filesystem paths directly in business tables, which is why moving its storage
 * would mean rewriting every module that ever displayed a picture. An opaque id
 * makes storage a media-module decision that can change without anyone else
 * noticing.
 */
public record MediaAssetId(UUID value) {

    public MediaAssetId {
        Objects.requireNonNull(value, "A media asset id is required");
    }

    public static MediaAssetId of(String value) {
        return new MediaAssetId(UUID.fromString(value));
    }

    public static MediaAssetId generate() {
        return new MediaAssetId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
