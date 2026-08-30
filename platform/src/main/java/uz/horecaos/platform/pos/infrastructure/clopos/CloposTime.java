package uz.horecaos.platform.pos.infrastructure.clopos;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Reads the several time formats Clopos uses.
 *
 * <p>There are at least four in one API, and they are not distinguishable by
 * field name. Resource timestamps such as {@code created_at} are
 * {@code YYYY-MM-DD HH:mm:ss} strings with no offset. The authentication
 * response's {@code expires_at} is a Unix second count. The <em>error</em> body
 * for an expired token carries {@code expires_at} as an ISO 8601 string — the
 * same field name as the success response, with a different type. And the stop
 * list's {@code timestamp} is Unix milliseconds.
 *
 * <p>The offsetless strings are read as UTC, which is a stated assumption rather
 * than a fact: Clopos documents no zone for them, and its own examples come from
 * a brand in another country. Everything this platform does with the value —
 * bounding a recovery search, ordering a candidate list — tolerates being a few
 * hours out, and nothing prices or promises from it. If that ever changes, the
 * zone has to be established with Clopos first, and this comment is where the
 * next reader finds out that it has not been.
 */
public final class CloposTime {

    private static final DateTimeFormatter RESOURCE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CloposTime() {
    }

    /** @return null for anything unparseable, because a wrong instant is worse than none */
    public static Instant parse(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        String trimmed = value.strip();
        try {
            return LocalDateTime.parse(trimmed, RESOURCE_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException notResourceTime) {
            // Fall through rather than fail: the same shape of field is ISO 8601
            // in at least one error body.
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notIso) {
            return null;
        }
    }
}
