package uz.horecaos.platform.payments.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The two timestamp conversions every store here needs.
 *
 * <p>Shared rather than repeated because {@code getObject(column, Instant.class)}
 * is not portable and {@code getTimestamp} applies the JVM's default zone. Reading
 * an {@code OffsetDateTime} and converting is the one form that means the same
 * thing on every machine, which matters more than usual on a module whose
 * timestamps decide whether a twelve-hour window has closed.
 */
final class PaymentTimestamps {

    private PaymentTimestamps() {}

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Null-safe, because a nullable timestamptz read through getTimestamp would not be. */
    static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
