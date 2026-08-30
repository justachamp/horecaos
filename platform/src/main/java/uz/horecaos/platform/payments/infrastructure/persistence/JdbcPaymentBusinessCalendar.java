package uz.horecaos.platform.payments.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.payments.application.PaymentBusinessCalendar;

/**
 * Reads the branch's IANA zone and answers the local date (ADR 0013).
 *
 * <p>Falls back to UTC when a location has no resolvable zone, and does so loudly
 * in one respect: the fallback is a date that may be wrong by a day, and Click's
 * resolver reads a wrong date as "no payment found". That is why a not-found from
 * {@code status_by_mti} never unblocks a retry — the answer may be about the wrong
 * day rather than about the wrong payment.
 */
@Repository
public class JdbcPaymentBusinessCalendar implements PaymentBusinessCalendar {

    private final JdbcClient jdbc;

    public JdbcPaymentBusinessCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LocalDate businessDateFor(UUID tenantId, UUID locationId, Instant at) {
        Optional<String> zone = jdbc.sql("""
                SELECT timezone
                FROM tenant.locations
                WHERE tenant_id = :tenantId AND id = :locationId
                """)
                .param("tenantId", tenantId).param("locationId", locationId)
                .query(String.class)
                .optional();

        return LocalDate.ofInstant(at, zone.map(JdbcPaymentBusinessCalendar::zoneOrUtc)
                .orElse(ZoneOffset.UTC));
    }

    private static ZoneId zoneOrUtc(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException unknownZone) {
            return ZoneOffset.UTC;
        }
    }
}
