package uz.horecaos.platform.media.api;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Server-side media ingestion for a caller that already has bytes, or a URL to
 * fetch them from, rather than a client uploading through the presigned
 * lifecycle {@link ObjectStorage#presignUpload} exists for (ADR 0010).
 *
 * <p>{@code catalog}'s CSV import (row 4.5b) is the first caller: a merchant's
 * spreadsheet names a product photo by URL, not by an already-finalized {@link
 * MediaAssetId} the way an {@code attachMedia}-style call expects, and there is
 * no client on the other end of a request to hand a presigned PUT to. This port
 * skips straight to the same decision {@code MediaVerificationWorker} makes for
 * an ordinary upload: sniff the real content type and dimensions from the
 * bytes' own header, weigh them against the same cost budget, and only then let
 * the object reach {@code AVAILABLE}.
 *
 * <p><b>Never trust the caller's or the URL's word for the content type.</b>
 * That is not a separate rule invented here — it is the same rule {@code
 * verifyUpload} has always enforced against a client's claimed upload, applied
 * once more against a server-side claimed download. A declared type or a
 * response {@code Content-Type} header is discarded the moment the bytes are in
 * hand; only {@code ImageProbe}'s reading of the bytes themselves decides.
 */
public interface MediaAssetIngestion {

    /**
     * Fetches {@code url} and ingests it if — and only if — it turns out to be
     * an acceptable image once the bytes are actually read.
     *
     * <p>The fetch itself is bounded (a size cap enforced while streaming, not
     * merely trusted from a {@code Content-Length} header) and scoped to
     * {@code http}/{@code https}. No domain or IP allowlist is enforced here by
     * design (row 4.5b: "allowlist-free but size/type-capped") — this is not a
     * general-purpose outbound HTTP capability, and the caller is expected to be
     * an operator naming their own product photo host, not an untrusted third
     * party.
     */
    IngestOutcome ingestFromUrl(
            UUID tenantId,
            OwnerScope ownerScope,
            UUID ownerId,
            boolean publicVisibility,
            URI url,
            @Nullable UUID actorId);

    /** Ingests bytes the caller already holds, running the identical validation {@link #ingestFromUrl} runs after its own fetch. */
    IngestOutcome ingestFromBytes(
            UUID tenantId,
            OwnerScope ownerScope,
            UUID ownerId,
            boolean publicVisibility,
            byte[] content,
            @Nullable String originalFilename,
            @Nullable UUID actorId);

    /**
     * The checksum verified for an already-ingested asset, or empty when it
     * does not exist for this tenant.
     *
     * <p>Exists so a caller that re-ingests the same content on every call
     * (row 4.5b: a CSV re-imported with an {@code image_url} column that
     * happens not to have changed) can tell "the same file, re-submitted"
     * from "a real change" and skip re-attaching it, without this port
     * exposing anything about where or how the asset is stored -- narrow in
     * the same spirit {@link MediaAvailability}'s own doc explains for why
     * it hands back a boolean rather than the assets themselves.
     */
    Optional<String> checksumOf(UUID tenantId, MediaAssetId assetId);

    /** Mirrors {@code media.domain.MediaOwner.Scope} — restated here so a caller outside {@code media} never imports it. */
    enum OwnerScope {
        TENANT,
        BRAND,
        LOCATION
    }

    /**
     * @param assetId       null when {@link #accepted} is false
     * @param rejectionCode null when {@link #accepted} is true; otherwise one of
     *                      the codes {@code MediaVerificationWorker}'s own
     *                      rejections already use ({@code SIZE_EXCEEDED}, {@code
     *                      CONTENT_NOT_AN_IMAGE}, {@code TYPE_NOT_ALLOWED},
     *                      {@code DIMENSIONS_EXCEEDED}) plus {@code
     *                      URL_NOT_ALLOWED} and {@code FETCH_FAILED}, which only
     *                      {@link #ingestFromUrl}'s own fetch step can produce
     */
    record IngestOutcome(
            boolean accepted,
            @Nullable MediaAssetId assetId,
            @Nullable String rejectionCode) {

        public static IngestOutcome accepted(MediaAssetId assetId) {
            return new IngestOutcome(true, assetId, null);
        }

        public static IngestOutcome rejected(String rejectionCode) {
            return new IngestOutcome(false, null, rejectionCode);
        }
    }
}
