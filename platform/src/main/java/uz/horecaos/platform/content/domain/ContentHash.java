package uz.horecaos.platform.content.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code content_hash} of a draft or a revision (ADR 0157, Physical model).
 *
 * <p>What makes "nothing to publish" mean <em>nothing</em>. The window, the channel set, the
 * promotion and the location live in columns beside the document, and an edit to any of them is a
 * real change -- extending a banner's end date must be an ordinary publish -- so the hash covers
 * all six values, not only the document: SHA-256, in hex, over the canonical serialisation of
 * {@code (document, channel_codes, starts_at, ends_at, promotion_id, location_id)}.
 *
 * <ul>
 *   <li>JSON with object keys sorted and no insignificant whitespace (see {@link CanonicalJson});
 *   <li>{@code channel_codes} sorted and de-duplicated, but an <em>empty</em> set stays empty: it
 *       means every content channel and is not the same as a list of every channel;
 *   <li>instants as UTC ISO-8601 with microseconds, the precision PostgreSQL stores;
 *   <li>absent values as {@code null}.
 * </ul>
 *
 * <p>It excludes the revision number, the author and the publication time, which differ on every
 * publish by construction. It is computed once, by the publish service, and stored with the
 * revision; a draft has no hash column, because it is compared only at the moment it is published.
 */
public final class ContentHash {

    private static final DateTimeFormatter MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private ContentHash() {}

    public static String of(
            JsonNode document,
            Collection<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {
        return sha256(CanonicalJson.write(envelope(document, channelCodes, startsAt, endsAt, promotionId, locationId)));
    }

    /** The canonical text that is hashed; exposed so a test can say what a hash is of. */
    public static String canonicalText(
            JsonNode document,
            Collection<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {
        return CanonicalJson.write(envelope(document, channelCodes, startsAt, endsAt, promotionId, locationId));
    }

    private static ObjectNode envelope(
            JsonNode document,
            Collection<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode envelope = nodes.objectNode();
        envelope.set("document", document);
        ArrayNode channels = nodes.arrayNode();
        for (String code : new TreeSet<>(channelCodes)) {
            channels.add(code);
        }
        envelope.set("channelCodes", channels);
        envelope.put("startsAt", startsAt == null ? null : micros(startsAt));
        envelope.put("endsAt", endsAt == null ? null : micros(endsAt));
        envelope.put("promotionId", promotionId == null ? null : promotionId.toString());
        envelope.put("locationId", locationId == null ? null : locationId.toString());
        return envelope;
    }

    private static String micros(Instant instant) {
        return MICROS.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
