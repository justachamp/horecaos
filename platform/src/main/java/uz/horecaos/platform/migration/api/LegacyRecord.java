package uz.horecaos.platform.migration.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One row as the legacy database spells it (ADR 0024).
 *
 * <p>Deliberately untyped. A record per legacy table would be sixty-four classes
 * mirroring a schema this platform is retiring, and each would have to be
 * corrected on the day production turns out to differ from
 * {@code docs/domains/legacy-profile-findings.md} — which the findings themselves
 * expect, since everything they mark volumetric is re-asked against production
 * before its wave runs. The typing that matters happens one step later, in the
 * transformation, where a missing or malformed value becomes a quarantine reason
 * rather than a {@code ClassCastException}.
 *
 * <p><strong>This holds source data and must not leave the transformation.</strong>
 * ADR 0029 applies to legacy rows exactly as it applies to target ones: no
 * personal data in a log, an event, a trace or a metric, and ADR 0024 adds that
 * no source payload is copied into the control plane. Quarantine takes a reason
 * code and a reference to protected evidence, never this object. {@link
 * #toString()} is overridden to make an accidental interpolation harmless.
 *
 * @param stableKey     the primary key as text, which is what the crosswalk and
 *                      the extraction cursor both key on. Text because the legacy
 *                      estate keys on bigints, uuids and strings, and coercing
 *                      them into one type is where a crosswalk starts inventing
 *                      identities
 * @param sourceVersion whatever this source calls a version — a row timestamp, a
 *                      revision, an ETag — compared as an opaque token and never
 *                      ordered, or null where the source versions nothing
 * @param values        column name to JDBC value, with SQL NULL present as a null
 *                      entry rather than an absent key, so "the column does not
 *                      exist" stays distinguishable from "the column is empty"
 */
public record LegacyRecord(String stableKey, String sourceVersion, Map<String, Object> values) {

    public LegacyRecord {
        Objects.requireNonNull(stableKey, "A legacy row needs its stable key");
        if (stableKey.isBlank()) {
            throw new IllegalArgumentException("A blank stable key cannot be paged from or crosswalked to");
        }
        Objects.requireNonNull(values, "Column values are required");
        // Not Map.copyOf: SQL NULL arrives as a null value and Map.copyOf rejects
        // it, which would turn every nullable column in the legacy schema into an
        // extraction failure.
        values = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(values));
    }

    /** Whether the column exists on this row at all, as opposed to being empty. */
    public boolean has(String column) {
        return values.containsKey(column);
    }

    public boolean isNull(String column) {
        return require(column) == null;
    }

    public String text(String column) {
        Object value = require(column);
        return value == null ? null : value.toString();
    }

    public UUID uuid(String column) {
        Object value = require(column);
        return switch (value) {
            case null -> null;
            case UUID id -> id;
            case String text -> UUID.fromString(text);
            default -> throw new IllegalArgumentException("Column %s is not a uuid".formatted(column));
        };
    }

    /**
     * A whole number, or null for SQL NULL.
     *
     * <p>Boxed {@link Long} rather than {@code long} for the reason the house
     * rules give about {@code getInt}: a primitive would answer 0 for SQL NULL,
     * and 0 is a legal quantity, a legal price, and a legal foreign key nowhere.
     */
    public Long number(String column) {
        Object value = require(column);
        return switch (value) {
            case null -> null;
            case Number n -> n.longValue();
            case String text -> Long.valueOf(text.strip());
            default -> throw new IllegalArgumentException("Column %s is not a whole number".formatted(column));
        };
    }

    /**
     * A naive legacy timestamp, exactly as stored and with no zone attached.
     *
     * <p>Returned as {@link LocalDateTime} on purpose. The legacy {@code
     * BaseModel} types every {@code created} and {@code updated} without a
     * timezone and defaults them to {@code datetime.now}, so the value is the
     * local wall time of the legacy application server and the zone is recorded
     * nowhere. Handing this back as an {@link Instant} would require choosing a
     * zone here, and the only zone available at this layer is the JVM's — which
     * is how "every historical order is five hours out" happens without anybody
     * writing it down.
     *
     * <p>Use {@link #instantAt(String, ZoneId)} with the program's configured
     * source zone.
     */
    public LocalDateTime naiveTimestamp(String column) {
        Object value = require(column);
        return switch (value) {
            case null -> null;
            case LocalDateTime local -> local;
            case java.sql.Timestamp stamp -> stamp.toLocalDateTime();
            default -> throw new IllegalArgumentException("Column %s is not a naive timestamp".formatted(column));
        };
    }

    /**
     * The same value as an instant, in the zone the program was configured with.
     *
     * <p>The zone is never defaulted. A program with no {@code source_time_zone}
     * has not had its deployment read yet, and extraction refuses to start rather
     * than assume UTC.
     */
    public Instant instantAt(String column, ZoneId sourceZone) {
        Objects.requireNonNull(
                sourceZone, "The legacy server's zone is required; a naive timestamp read without one is a guess");
        LocalDateTime naive = naiveTimestamp(column);
        return naive == null ? null : naive.atZone(sourceZone).toInstant();
    }

    private Object require(String column) {
        if (!values.containsKey(column)) {
            throw new IllegalArgumentException(
                    ("Column %s was not selected for this entity. Extraction selects a named list, "
                                    + "so a missing column is a mapping error and not an empty value.")
                            .formatted(column));
        }
        return values.get(column);
    }

    /**
     * The key and nothing else.
     *
     * <p>ADR 0029. The default record {@code toString} renders every column, and
     * one interpolation of that into a log line publishes a customer's phone
     * number to wherever logs are shipped.
     */
    @Override
    public String toString() {
        return "LegacyRecord[" + stableKey + ", " + values.size() + " columns]";
    }
}
