package uz.horecaos.platform.migration.infrastructure.persistence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The column conversions shared by the seven stores in this package.
 *
 * <p>Elsewhere on the platform each store carries its own copy of the two time
 * helpers, which costs nothing when the stores sit in different packages. Seven
 * copies inside one package would be seven places to correct on the day a
 * nullable column is read through the wrong accessor, and reading a nullable
 * column through the wrong accessor is exactly the defect these methods exist to
 * prevent.
 */
final class MigrationColumns {

    private MigrationColumns() {
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instantOrNull(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Reads a {@code numeric(38, 0)} as the exact integer it is.
     *
     * <p>Never {@code getLong}: it answers 0 for a SQL NULL, and a checksum rule
     * has no numeric sides at all, so the two absent values would read back as
     * "both sides measured zero and agreed" — a reconciliation that passes
     * because nothing was compared. {@code toBigIntegerExact} is the second half
     * of the same care: the column has no scale, so a value that arrived with one
     * is a mapping error and should say so rather than round.
     */
    static BigInteger exactIntegerOrNull(ResultSet row, String column) throws SQLException {
        BigDecimal value = row.getObject(column, BigDecimal.class);
        return value == null ? null : value.toBigIntegerExact();
    }

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() { };

    /**
     * Reads a {@code jsonb} object column as the map the application ports carry.
     *
     * <p>The ports type {@code checkpoint} and {@code evidence_snapshot} as maps
     * rather than as JSON text because the services read and write individual
     * keys in them — the state a held scope resumes into, the count of undecided
     * sources — and a service that had to re-parse a string to do that would be
     * deciding, in the application layer, how the control plane is serialized.
     *
     * <p>Both columns are {@code NOT NULL DEFAULT '{}'}, so an empty map is what
     * an absent document means and a null is only reachable through a column that
     * is not one of those two.
     */
    static Map<String, Object> documentOrEmpty(ObjectMapper mapper, ResultSet row, String column)
            throws SQLException {
        String json = row.getString(column);
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(mapper.readValue(json, DOCUMENT));
        } catch (JacksonException failure) {
            throw new IllegalStateException(
                    "Stored migration document in %s is not a JSON object".formatted(column), failure);
        }
    }

    /**
     * Renders a document for a {@code jsonb} column, treating null as empty.
     *
     * <p>Never null: both columns this serializes for are {@code NOT NULL}, and a
     * caller that has nothing to say about a checkpoint means {@code {}} rather
     * than a row the schema refuses.
     */
    static String documentJson(ObjectMapper mapper, Map<String, Object> document) {
        return mapper.writeValueAsString(document == null ? Map.of() : document);
    }
}
