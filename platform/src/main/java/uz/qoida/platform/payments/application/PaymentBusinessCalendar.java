package uz.qoida.platform.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The branch's own date (ADR 0013).
 *
 * <p>Needed for two things and both of them are money. The legal entity is
 * resolved on the order's business date, so an entity change dated to a Monday
 * must not retrospectively re-seller Sunday's orders. And Click's
 * {@code payment/status_by_mti} takes a trailing {@code YYYY-MM-DD} that is the
 * uncertainty resolver's argument — a wrong date reads as "no payment found",
 * which is precisely the answer that would make a retry look safe.
 *
 * <p>A UTC date would roll over at 05:00 in Tashkent, in the middle of a night
 * service, which is the same reason ordering resolves a branch timezone for its
 * daily order number.
 */
public interface PaymentBusinessCalendar {

    LocalDate businessDateFor(UUID tenantId, UUID locationId, Instant at);
}
