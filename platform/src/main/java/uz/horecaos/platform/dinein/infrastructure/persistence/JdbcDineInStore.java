package uz.horecaos.platform.dinein.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
import uz.horecaos.platform.dinein.domain.SessionOrigin;
import uz.horecaos.platform.dinein.domain.SessionStatus;

/**
 * Dine-in persistence (ADR 0047).
 *
 * <p>Four rules run through every statement here.
 *
 * <p>Every tenant-owned query carries the tenant predicate, with exactly one
 * documented exception: {@link #findTableByQrToken}, which is handed a digest by
 * somebody who has not said who they are and whose whole job is to discover the
 * tenant. That query is safe because the digest is the credential — it is the
 * SHA-256 of 128 uniform bits, globally unique by constraint — and every query
 * downstream of it carries the tenant it returned.
 *
 * <p>Every state change is a conditional UPDATE naming the status it expects and
 * the version it read, and the row count decides who won. Two hosts seating one
 * booking, a waiter and a manager closing the same table, and a retried request
 * all reduce to that question and are answered by PostgreSQL.
 *
 * <p>Nothing here writes an order. {@code dinein.session_orders} names orders; the
 * only read that crosses into {@code ordering} is the session total, and it lives
 * in {@code infrastructure.ordering} and is a read.
 *
 * <p>Nullable integer columns are read with {@code getObject(..., Integer.class)}
 * throughout. {@code getInt} answers 0 for SQL NULL, and a zero
 * {@code service_charge_rate_bp_snapshot} on a session that never had one is a bill
 * that silently drops a charge somebody is owed.
 */
@Repository
public class JdbcDineInStore {

    /** The name V0034 gives the double-booking exclusion constraint. */
    public static final String DOUBLE_BOOKING_CONSTRAINT = "ex_reservation_table_no_double_booking";

    /** The name V0034 gives the one-party-per-table index. */
    public static final String TABLE_OCCUPIED_INDEX = "ux_session_table_occupied";

    /** The name V0467 gives the one-live-unconfirmed-claim-per-account-per-branch index (ADR 0143). */
    public static final String CLAIM_ACCOUNT_INDEX = "ux_claim_account_branch";

    private final JdbcClient jdbc;

    public JdbcDineInStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------- settings

    public Optional<SettingsRow> findSettings(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT %s
                FROM dinein.location_settings
                WHERE tenant_id = :tenantId AND location_id = :locationId
                """.formatted(SETTINGS_COLUMNS))
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcDineInStore::mapSettings)
                .optional();
    }

    /**
     * The branch's settings row, locked for the rest of the transaction (ADR 0143).
     *
     * <p>{@code FOR NO KEY UPDATE}, the lock a plain {@code UPDATE} of a non-key
     * column takes: it queues behind an in-flight claim and the other way round, and
     * it does not block a foreign key check on the tenant's other rows. The row is
     * the one thing every claim at a branch has in common, which is what lets the
     * branch cap and the daily cap (aggregates over rows that do not exist yet) be
     * counted rather than assumed -- see {@code WalkInSeatingService}. A branch with
     * no row is off, and there is nothing to lock.
     */
    public Optional<SettingsRow> lockSettings(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT %s
                FROM dinein.location_settings
                WHERE tenant_id = :tenantId AND location_id = :locationId
                FOR NO KEY UPDATE
                """.formatted(SETTINGS_COLUMNS))
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcDineInStore::mapSettings)
                .optional();
    }

    /**
     * Creates the branch's settings row on first use.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an upsert: a branch has exactly
     * one of these, and two managers configuring a never-configured branch in the
     * same second must produce one row and one refusal, not two writes of which the
     * second silently wins (ADR 0031's expected version has to mean something on the
     * first write too).
     *
     * @return whether this call created the row
     */
    public boolean insertSettings(SettingsRow settings, Instant now) {
        return jdbc.sql("""
                INSERT INTO dinein.location_settings (
                    tenant_id, brand_id, location_id, qr_mode, turnaround_minutes,
                    guest_session_ttl_minutes, service_charge_rate_bp,
                    walk_in_self_seat, walk_in_claim_ttl_minutes, walk_in_horizon_minutes,
                    walk_in_max_unconfirmed, walk_in_daily_claims_per_account,
                    walk_in_payment_defer_minutes, session_currency,
                    version, created_at, updated_at)
                VALUES (:tenantId, :brandId, :locationId, :qrMode, :turnaround,
                    :ttl, :serviceCharge,
                    :selfSeat, :claimTtl, :horizon, :maxUnconfirmed, :dailyClaims,
                    :paymentDefer, :currency,
                    1, :now, :now)
                ON CONFLICT (location_id) DO NOTHING
                """)
                        .param("tenantId", settings.tenantId())
                        .param("brandId", settings.brandId())
                        .param("locationId", settings.locationId())
                        .param("qrMode", settings.qrMode().name())
                        .param("turnaround", settings.turnaroundMinutes())
                        .param("ttl", settings.guestSessionTtlMinutes())
                        .param("serviceCharge", settings.serviceChargeRateBp())
                        .param("selfSeat", settings.walkIn().selfSeat())
                        .param("claimTtl", settings.walkIn().claimTtlMinutes())
                        .param("horizon", settings.walkIn().horizonMinutes())
                        .param("maxUnconfirmed", settings.walkIn().maxUnconfirmed())
                        .param("dailyClaims", settings.walkIn().dailyClaimsPerAccount())
                        .param("paymentDefer", settings.walkIn().paymentDeferMinutes())
                        .param("currency", settings.sessionCurrency())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Rewrites the branch's settings, conditionally on the version the caller read
     * (ADR 0031), so two managers editing one branch's settings produce one change
     * and one stale-version refusal rather than a silent last-writer-wins.
     *
     * @return whether the row moved
     */
    public boolean updateSettings(SettingsRow settings, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE dinein.location_settings
                   SET qr_mode = :qrMode,
                       turnaround_minutes = :turnaround,
                       guest_session_ttl_minutes = :ttl,
                       service_charge_rate_bp = :serviceCharge,
                       walk_in_self_seat = :selfSeat,
                       walk_in_claim_ttl_minutes = :claimTtl,
                       walk_in_horizon_minutes = :horizon,
                       walk_in_max_unconfirmed = :maxUnconfirmed,
                       walk_in_daily_claims_per_account = :dailyClaims,
                       walk_in_payment_defer_minutes = :paymentDefer,
                       session_currency = :currency,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND location_id = :locationId AND version = :expectedVersion
                """)
                        .param("qrMode", settings.qrMode().name())
                        .param("turnaround", settings.turnaroundMinutes())
                        .param("ttl", settings.guestSessionTtlMinutes())
                        .param("serviceCharge", settings.serviceChargeRateBp())
                        .param("selfSeat", settings.walkIn().selfSeat())
                        .param("claimTtl", settings.walkIn().claimTtlMinutes())
                        .param("horizon", settings.walkIn().horizonMinutes())
                        .param("maxUnconfirmed", settings.walkIn().maxUnconfirmed())
                        .param("dailyClaims", settings.walkIn().dailyClaimsPerAccount())
                        .param("paymentDefer", settings.walkIn().paymentDeferMinutes())
                        .param("currency", settings.sessionCurrency())
                        .param("now", utc(now))
                        .param("tenantId", settings.tenantId())
                        .param("locationId", settings.locationId())
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /**
     * The branch's own timezone, for the session's business date.
     *
     * <p>A one-column read into {@code tenant.locations} rather than a dependency
     * on the tenancy module: this is the same fact V0003 already stores once, and
     * a second copy on a dinein row would be a timezone that stopped agreeing with
     * the branch the first time one moved.
     *
     * <p>No fallback to UTC. A branch with no timezone cannot have a trading day,
     * and inventing one puts an evening's takings on whichever calendar day
     * Greenwich happened to be having.
     */
    public Optional<String> locationTimeZone(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT timezone FROM tenant.locations
                WHERE tenant_id = :tenantId AND id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((row, number) -> row.getString("timezone"))
                .optional();
    }

    // -------------------------------------------------------------- sections

    public void insertSection(SectionRow section, Instant now) {
        jdbc.sql("""
                INSERT INTO dinein.sections (
                    id, tenant_id, brand_id, location_id, code, display_name,
                    sort_order, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :code, :displayName,
                    :sortOrder, :status, 1, :now, :now)
                """)
                .param("id", section.id())
                .param("tenantId", section.tenantId())
                .param("brandId", section.brandId())
                .param("locationId", section.locationId())
                .param("code", section.code())
                .param("displayName", section.displayName())
                .param("sortOrder", section.sortOrder())
                .param("status", section.status())
                .param("now", utc(now))
                .update();
    }

    public List<SectionRow> listSections(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, code, display_name,
                       sort_order, status, version
                FROM dinein.sections
                WHERE tenant_id = :tenantId AND location_id = :locationId
                ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcDineInStore::mapSection)
                .list();
    }

    // ---------------------------------------------------------------- tables

    public void insertTable(TableRow table, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", table.id());
        params.put("tenantId", table.tenantId());
        params.put("brandId", table.brandId());
        params.put("locationId", table.locationId());
        params.put("sectionId", table.sectionId());
        params.put("code", table.code());
        params.put("displayName", table.displayName());
        params.put("seats", table.seats());
        params.put("joinable", table.joinable());
        params.put("layoutX", table.layoutX());
        params.put("layoutY", table.layoutY());
        params.put("status", table.status());
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO dinein.tables (
                    id, tenant_id, brand_id, location_id, section_id, code, display_name,
                    seats, joinable, layout_x, layout_y, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :sectionId, :code, :displayName,
                    :seats, :joinable, :layoutX, :layoutY, :status, 1, :now, :now)
                """).params(params).update();
    }

    public Optional<TableRow> findTable(UUID tenantId, UUID tableId) {
        return jdbc.sql(SELECT_TABLE + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", tableId)
                .query(JdbcDineInStore::mapTable)
                .optional();
    }

    public List<TableRow> listTables(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_TABLE + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY section_id, code
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcDineInStore::mapTable)
                .list();
    }

    /**
     * The one query in this class with no tenant predicate, and it must be.
     *
     * <p>A scan presents a digest and nothing else. There is no tenant to filter
     * by until this row returns one, which is exactly what it is for. The safety
     * comes from the value rather than from the predicate: the digest is over 128
     * uniform bits and V0034 makes it globally unique, so no two tenants can share
     * one and there is nothing in the input to walk towards a neighbour's table.
     *
     * <p>An archived table answers nothing. A code on a table that has been taken
     * out of the room must stop working even if somebody kept the card.
     */
    public Optional<TableRow> findTableByQrToken(String tokenHash) {
        return jdbc.sql(SELECT_TABLE + " WHERE qr_token_hash = :hash AND status = 'ACTIVE'")
                .param("hash", tokenHash)
                .query(JdbcDineInStore::mapTable)
                .optional();
    }

    /**
     * Points a table at a new QR digest.
     *
     * <p>Conditional on the version the caller read, so two managers rotating one
     * table's code in the same minute produce one new card rather than two, the
     * second of which nobody printed.
     *
     * @return whether the row moved
     */
    public boolean rotateQrToken(UUID tenantId, UUID tableId, int expectedVersion, String tokenHash, Instant now) {

        return jdbc.sql("""
                UPDATE dinein.tables
                   SET qr_token_hash = :hash,
                       qr_token_rotated_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                   AND status <> 'ARCHIVED'
                """)
                        .param("hash", tokenHash)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", tableId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    public boolean updateTableStatus(UUID tenantId, UUID tableId, int expectedVersion, String status, Instant now) {

        return jdbc.sql("""
                UPDATE dinein.tables
                   SET status = :status, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("status", status)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", tableId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /**
     * Moves a table on the canvas (drag-to-reposition), conditionally on the
     * version the caller read — the same optimistic-locking shape as {@link
     * #updateTableStatus} and {@link #rotateQrToken}.
     *
     * @return whether the row moved
     */
    public boolean updateTableLayout(
            UUID tenantId, UUID tableId, int expectedVersion, BigDecimal layoutX, BigDecimal layoutY, Instant now) {

        return jdbc.sql("""
                UPDATE dinein.tables
                   SET layout_x = :layoutX, layout_y = :layoutY, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("layoutX", layoutX)
                        .param("layoutY", layoutY)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", tableId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    // -------------------------------------------------------- guest sessions

    public void insertGuestSession(GuestSessionRow guest) {
        jdbc.sql("""
                INSERT INTO dinein.qr_guest_sessions (
                    id, tenant_id, brand_id, location_id, table_id, token_hash,
                    qr_mode_snapshot, issued_at, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :tableId, :hash,
                    :mode, :issuedAt, :expiresAt)
                """)
                .param("id", guest.id())
                .param("tenantId", guest.tenantId())
                .param("brandId", guest.brandId())
                .param("locationId", guest.locationId())
                .param("tableId", guest.tableId())
                .param("hash", guest.tokenHash())
                .param("mode", guest.qrMode().name())
                .param("issuedAt", utc(guest.issuedAt()))
                .param("expiresAt", utc(guest.expiresAt()))
                .update();
    }

    /**
     * Resolves a presented guest token.
     *
     * <p>No tenant predicate for the same reason as the table lookup, and the same
     * mitigation: the digest is the credential and the row is what says whose
     * dining room this is. Expiry and revocation are in the predicate rather than
     * checked afterwards, so there is no window in which a caller reads a revoked
     * row and forgets to look.
     */
    public Optional<GuestSessionRow> findLiveGuestSession(String tokenHash, Instant now) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, table_id, token_hash,
                       qr_mode_snapshot, issued_at, expires_at, revoked_at, revoked_reason
                FROM dinein.qr_guest_sessions
                WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > :now
                """)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .query(JdbcDineInStore::mapGuestSession)
                .optional();
    }

    /**
     * Kills every live token minted from one table's code.
     *
     * <p>Called in the same transaction that writes a new digest, which is what
     * makes rotation immediate rather than eventual. Without it a photographed
     * code would keep working through whatever remained of its guests' four hours,
     * and the operator who rotated it would believe otherwise.
     */
    public int revokeGuestSessionsForTable(UUID tenantId, UUID tableId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE dinein.qr_guest_sessions
                   SET revoked_at = :now, revoked_reason = :reason
                 WHERE tenant_id = :tenantId AND table_id = :tableId AND revoked_at IS NULL
                """)
                .param("now", utc(now))
                .param("reason", reason)
                .param("tenantId", tenantId)
                .param("tableId", tableId)
                .update();
    }

    // ---------------------------------------------------------- reservations

    public void insertReservation(ReservationRow reservation, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", reservation.id());
        params.put("tenantId", reservation.tenantId());
        params.put("brandId", reservation.brandId());
        params.put("locationId", reservation.locationId());
        params.put("customerAccountId", reservation.customerAccountId());
        params.put("name", reservation.guestNameEncrypted());
        params.put("phone", reservation.guestPhoneEncrypted());
        params.put("phoneHash", reservation.guestPhoneLookupHash());
        params.put("secondary", reservation.secondaryPhoneEncrypted());
        params.put("note", reservation.noteEncrypted());
        params.put("partySize", reservation.partySize());
        params.put("from", utc(reservation.requestedFrom()));
        params.put("to", utc(reservation.requestedTo()));
        params.put("turnaround", reservation.turnaroundMinutes());
        params.put("status", reservation.status().name());
        params.put("channelId", reservation.sourceChannelId());
        params.put("createdBy", reservation.createdBy());
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO dinein.reservations (
                    id, tenant_id, brand_id, location_id, customer_account_id,
                    guest_name_encrypted, guest_phone_encrypted, guest_phone_lookup_hash,
                    secondary_phone_encrypted, note_encrypted, party_size,
                    requested_from, requested_to, turnaround_minutes_snapshot,
                    status, source_channel_id, created_by, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :customerAccountId,
                    :name, :phone, :phoneHash, :secondary, :note, :partySize,
                    :from, :to, :turnaround, :status, :channelId, :createdBy, 1, :now, :now)
                """).params(params).update();
    }

    /**
     * Attaches the tables a booking wants, with the effective hold each one takes.
     *
     * <p>The copied status is not supplied: V0034's insert trigger takes it from
     * the parent booking, so no caller can write a hold whose status disagrees
     * with the booking it belongs to.
     */
    public void insertReservationTable(
            UUID reservationId, UUID tableId, UUID tenantId, UUID locationId, Instant heldFrom, Instant heldTo) {

        jdbc.sql("""
                INSERT INTO dinein.reservation_tables (
                    reservation_id, table_id, tenant_id, location_id, held_during, status)
                VALUES (:reservationId, :tableId, :tenantId, :locationId,
                    tstzrange(:heldFrom::timestamptz, :heldTo::timestamptz, '[)'), 'REQUESTED')
                """)
                .param("reservationId", reservationId)
                .param("tableId", tableId)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("heldFrom", utc(heldFrom))
                .param("heldTo", utc(heldTo))
                .update();
    }

    /**
     * Re-snapshots the effective hold before a booking is confirmed.
     *
     * <p>Run as its own statement, immediately before the status moves, and the
     * ordering matters. It is the status change that the exclusion constraint
     * checks, so writing the interval first means a confirmation is never checked
     * against an interval built from last month's turnaround buffer.
     */
    public void rewriteHolds(UUID tenantId, UUID reservationId, Instant heldFrom, Instant heldTo) {
        jdbc.sql("""
                UPDATE dinein.reservation_tables
                   SET held_during = tstzrange(:heldFrom::timestamptz, :heldTo::timestamptz, '[)')
                 WHERE tenant_id = :tenantId AND reservation_id = :reservationId
                """)
                .param("heldFrom", utc(heldFrom))
                .param("heldTo", utc(heldTo))
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .update();
    }

    public Optional<ReservationRow> findReservation(UUID tenantId, UUID reservationId) {
        return jdbc.sql(SELECT_RESERVATION + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", reservationId)
                .query(JdbcDineInStore::mapReservation)
                .optional();
    }

    /**
     * The same lookup, additionally scoped to one branch.
     *
     * <p>A reservation id is a UUID a client supplies on a path already scoped to
     * {@code tenantId}/{@code locationId}; a lookup that ignored the path's own
     * {@code locationId} would serve — and let a location-scoped operator read or
     * amend — a booking belonging to a branch they hold no grant over. Callers
     * that already know they are inside the right branch (an internal re-read
     * after a write this same request already validated) may still use the
     * two-argument overload above.
     */
    public Optional<ReservationRow> findReservationAtLocation(UUID tenantId, UUID locationId, UUID reservationId) {
        return jdbc.sql(SELECT_RESERVATION + " WHERE tenant_id = :tenantId AND location_id = :locationId AND id = :id")
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("id", reservationId)
                .query(JdbcDineInStore::mapReservation)
                .optional();
    }

    public List<UUID> tablesForReservation(UUID tenantId, UUID reservationId) {
        return jdbc.sql("""
                SELECT table_id FROM dinein.reservation_tables
                WHERE tenant_id = :tenantId AND reservation_id = :reservationId
                ORDER BY table_id
                """)
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .query((row, number) -> row.getObject("table_id", UUID.class))
                .list();
    }

    /**
     * Moves a booking, conditionally on the status and version the caller read.
     *
     * <p>The trigger on this table's {@code status} carries the change onto the
     * holds, and it is there that the exclusion constraint fires. A caller
     * confirming a booking therefore sees either one updated row or a constraint
     * violation, never a silent overlap.
     *
     * @return whether the row moved
     */
    public boolean moveReservation(
            UUID tenantId,
            UUID reservationId,
            ReservationStatus from,
            ReservationStatus to,
            int expectedVersion,
            Instant now) {

        return jdbc.sql("""
                UPDATE dinein.reservations
                   SET status = :to, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = :from AND version = :expectedVersion
                """)
                        .param("to", to.name())
                        .param("from", from.name())
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", reservationId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /**
     * A branch's bookings overlapping a window, oldest first.
     *
     * <p>Unlike {@link #tableAvailability}, this carries every status — a host
     * reading tonight's plan needs to see a cancellation sitting beside a
     * confirmed booking, not just what still holds a table. No guest name, phone
     * or note is selected: the day view this backs is the same list surface
     * {@link ReservationRow} already renders through {@code ReservationResponse},
     * which carries none of it either.
     */
    public List<ReservationRow> listReservations(UUID tenantId, UUID locationId, Instant from, Instant to) {
        return jdbc.sql(SELECT_RESERVATION + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND requested_from < :to AND requested_to > :from
                 ORDER BY requested_from
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(JdbcDineInStore::mapReservation)
                .list();
    }

    /**
     * Rewrites a booking's own fields — party size and the requested interval —
     * conditionally on the version the caller read.
     *
     * <p>Status is untouched here; {@link #moveReservation} owns that column
     * exclusively so the exclusion constraint's trigger always sees a status
     * change come through one statement shape.
     *
     * <p>The four guest columns are optional corrections, not a whole-object
     * write: a null {@code guestNameEncrypted}/{@code guestPhoneEncrypted}/
     * {@code guestPhoneLookupHash}/{@code noteEncrypted} means "the host did
     * not re-type this field", and {@code COALESCE} keeps whatever the booking
     * already had rather than blanking it. A non-null value replaces it in the
     * same statement {@link #moveReservation} keeps status confined to, so an
     * amendment that also corrects the guest's number is still one version
     * bump, not two racing against the caller's {@code If-Match}.
     *
     * @return whether the row moved
     */
    public boolean updateReservationCore(
            UUID tenantId,
            UUID reservationId,
            int partySize,
            Instant requestedFrom,
            Instant requestedTo,
            int turnaroundMinutesSnapshot,
            @Nullable String guestNameEncrypted,
            @Nullable String guestPhoneEncrypted,
            @Nullable String guestPhoneLookupHash,
            @Nullable String noteEncrypted,
            int expectedVersion,
            Instant now) {

        return jdbc.sql("""
                UPDATE dinein.reservations
                   SET party_size = :partySize,
                       requested_from = :from,
                       requested_to = :to,
                       turnaround_minutes_snapshot = :turnaround,
                       guest_name_encrypted = COALESCE(:guestName, guest_name_encrypted),
                       guest_phone_encrypted = COALESCE(:guestPhone, guest_phone_encrypted),
                       guest_phone_lookup_hash = COALESCE(:guestPhoneHash, guest_phone_lookup_hash),
                       note_encrypted = COALESCE(:note, note_encrypted),
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("partySize", partySize)
                        .param("from", utc(requestedFrom))
                        .param("to", utc(requestedTo))
                        .param("turnaround", turnaroundMinutesSnapshot)
                        .param("guestName", guestNameEncrypted)
                        .param("guestPhone", guestPhoneEncrypted)
                        .param("guestPhoneHash", guestPhoneLookupHash)
                        .param("note", noteEncrypted)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", reservationId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /**
     * Detaches every table a booking currently holds, so an amendment can
     * reattach the (possibly different) set it was submitted with in the same
     * transaction.
     */
    public void deleteReservationTables(UUID tenantId, UUID reservationId) {
        jdbc.sql("DELETE FROM dinein.reservation_tables WHERE tenant_id = :tenantId AND reservation_id = :id")
                .param("tenantId", tenantId)
                .param("id", reservationId)
                .update();
    }

    /**
     * Which of a branch's tables are free for an interval, and which are not.
     *
     * <p>Answers over the same predicate the exclusion constraint enforces, so the
     * availability a host is shown and the booking the database will accept cannot
     * disagree about anything except timing. It is still only advisory: two hosts
     * reading this in the same second both see a free table, and the constraint is
     * what decides between them.
     */
    public List<AvailabilityRow> tableAvailability(UUID tenantId, UUID locationId, Instant from, Instant to) {

        return jdbc.sql("""
                SELECT t.id AS table_id, t.code, t.seats, t.section_id, t.status,
                       EXISTS (
                           SELECT 1 FROM dinein.reservation_tables rt
                           WHERE rt.table_id = t.id
                             AND rt.status IN ('CONFIRMED', 'SEATED')
                             AND rt.held_during && tstzrange(:from::timestamptz, :to::timestamptz, '[)')
                       ) AS booked,
                       EXISTS (
                           SELECT 1 FROM dinein.session_tables st
                           WHERE st.table_id = t.id AND st.left_at IS NULL
                       ) AS occupied
                FROM dinein.tables t
                WHERE t.tenant_id = :tenantId AND t.location_id = :locationId
                  AND t.status = 'ACTIVE'
                ORDER BY t.code
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((row, number) -> new AvailabilityRow(
                        row.getObject("table_id", UUID.class),
                        row.getString("code"),
                        row.getInt("seats"),
                        row.getObject("section_id", UUID.class),
                        row.getString("status"),
                        row.getBoolean("booked"),
                        row.getBoolean("occupied")))
                .list();
    }

    // -------------------------------------------------------------- sessions

    public void insertSession(SessionRow session, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", session.id());
        params.put("tenantId", session.tenantId());
        params.put("brandId", session.brandId());
        params.put("locationId", session.locationId());
        params.put("reservationId", session.reservationId());
        params.put("partySize", session.partySize());
        params.put("businessDate", session.businessDate());
        params.put("openedBy", session.openedBy());
        params.put("openedAt", utc(session.openedAt()));
        params.put("status", session.status().name());
        params.put("serviceCharge", session.serviceChargeRateBpSnapshot());
        params.put("currency", session.currency());
        // The claim columns travel in the INSERT, not in a second statement (ADR 0143,
        // Eligibility step 8): a session that is a claim for even one statement is a
        // session the sweeper and the unique index cannot see.
        params.put("origin", session.origin().name());
        params.put("claimant", session.openedByAccountId());
        params.put("claimExpiresAt", nullableUtc(session.claimExpiresAt()));
        params.put("confirmedAt", nullableUtc(session.confirmedAt()));
        params.put("confirmedBy", session.confirmedBy());
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO dinein.table_sessions (
                    id, tenant_id, brand_id, location_id, reservation_id, party_size,
                    business_date, opened_by, opened_at, status,
                    service_charge_rate_bp_snapshot, currency,
                    origin, opened_by_account_id, claim_expires_at, confirmed_at, confirmed_by,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :reservationId, :partySize,
                    :businessDate, :openedBy, :openedAt, :status,
                    :serviceCharge, :currency,
                    :origin, :claimant, :claimExpiresAt, :confirmedAt, :confirmedBy,
                    1, :now, :now)
                """).params(params).update();
    }

    /**
     * Records that a session sits at a table.
     *
     * @param joinedAt when the table was joined. It is also the only thing that
     *                 orders a party's tables ({@link #tablesForSession}, {@link
     *                 #tablesForOrders}), so a caller joining several at once
     *                 stamps each a distinct instant, in join order -- see {@code
     *                 TableSessionService.open}
     */
    public void occupyTable(UUID sessionId, UUID tableId, UUID tenantId, UUID locationId, Instant joinedAt) {

        jdbc.sql("""
                INSERT INTO dinein.session_tables (
                    session_id, table_id, tenant_id, location_id, joined_at)
                VALUES (:sessionId, :tableId, :tenantId, :locationId, :joinedAt)
                """)
                .param("sessionId", sessionId)
                .param("tableId", tableId)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("joinedAt", utc(joinedAt))
                .update();
    }

    public Optional<SessionRow> findSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql(SELECT_SESSION + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .query(JdbcDineInStore::mapSession)
                .optional();
    }

    /**
     * One session, locked for the rest of the transaction (ADR 0143).
     *
     * <p>Taken by the two paths that can disagree about a claim's fate on the same
     * row: attaching a round to it, and the sweeper deciding to lapse or confirm it.
     * Serialized on this lock they have exactly one outcome -- the sweeper decides on
     * a view that already contains the round, or the attach finds a session the
     * sweeper has closed and is refused with the stable "takes no more rounds"
     * answer -- rather than a round attached to a table that was just given back.
     */
    public Optional<SessionRow> lockSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql(SELECT_SESSION + " WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .query(JdbcDineInStore::mapSession)
                .optional();
    }

    /** The live session at a table, which is what a scanned code resolves to. */
    public Optional<SessionRow> findLiveSessionAtTable(UUID tenantId, UUID tableId) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE tenant_id = :tenantId
                   AND status IN ('OPEN', 'BILL_REQUESTED', 'SETTLING')
                   AND id IN (SELECT session_id FROM dinein.session_tables
                              WHERE table_id = :tableId AND left_at IS NULL)
                """)
                .param("tenantId", tenantId)
                .param("tableId", tableId)
                .query(JdbcDineInStore::mapSession)
                .optional();
    }

    public List<SessionRow> listLiveSessions(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND status IN ('OPEN', 'BILL_REQUESTED', 'SETTLING')
                 ORDER BY opened_at
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcDineInStore::mapSession)
                .list();
    }

    /**
     * Moves a session, conditionally on the status and version the caller read.
     *
     * <p>{@code closedAt} and {@code settledTotalMinor} travel with the move rather
     * than in a second statement, because V0034 states the pair completeness as an
     * equality: a status that closes and an instant that does not are not two rows
     * apart, they are one row that no CHECK will accept.
     *
     * @return whether the row moved
     */
    public boolean moveSession(
            UUID tenantId,
            UUID sessionId,
            SessionStatus from,
            SessionStatus to,
            int expectedVersion,
            @Nullable Instant closedAt,
            @Nullable Long settledTotalMinor,
            @Nullable String closeReasonCode,
            Instant now) {
        return moveSession(
                tenantId,
                sessionId,
                from,
                to,
                expectedVersion,
                closedAt,
                settledTotalMinor,
                closeReasonCode,
                null,
                now);
    }

    /**
     * {@link #moveSession} that also confirms an unconfirmed guest claim in the same
     * conditional {@code UPDATE} (ADR 0143, Decision 4): a staff move of a claim past
     * {@code OPEN} means someone in the room has taken charge of the table, and that
     * fact and the move it follows must not be two statements a lapse can slip
     * between.
     *
     * @param confirmedBy the staff subject to record, or null to leave confirmation
     *                    alone. Ignored for a session that is not an unconfirmed
     *                    guest claim
     */
    public boolean moveSession(
            UUID tenantId,
            UUID sessionId,
            SessionStatus from,
            SessionStatus to,
            int expectedVersion,
            @Nullable Instant closedAt,
            @Nullable Long settledTotalMinor,
            @Nullable String closeReasonCode,
            @Nullable String confirmedBy,
            Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", sessionId);
        params.put("from", from.name());
        params.put("to", to.name());
        params.put("expectedVersion", expectedVersion);
        params.put("closedAt", nullableUtc(closedAt));
        params.put("settledTotal", settledTotalMinor);
        params.put("closeReason", closeReasonCode);
        params.put("confirmedBy", confirmedBy);
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE dinein.table_sessions
                   SET status = :to,
                       closed_at = :closedAt,
                       settled_total_minor = COALESCE(:settledTotal::bigint, settled_total_minor),
                       close_reason_code = COALESCE(:closeReason::varchar, close_reason_code),
                       confirmed_at = CASE
                           WHEN :confirmedBy::varchar IS NOT NULL
                                AND origin = 'GUEST_QR' AND confirmed_at IS NULL
                           THEN :now ELSE confirmed_at END,
                       confirmed_by = CASE
                           WHEN :confirmedBy::varchar IS NOT NULL
                                AND origin = 'GUEST_QR' AND confirmed_at IS NULL
                           THEN :confirmedBy::varchar ELSE confirmed_by END,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = :from AND version = :expectedVersion
                """).params(params).update() == 1;
    }

    /**
     * Confirms an unconfirmed guest claim without moving it (ADR 0143): the round the
     * restaurant accepted, or a member of staff keeping the table for a guest who has
     * not ordered yet.
     *
     * <p>Conditional on the version the caller read and on the claim still being
     * live and unconfirmed, so a confirmation racing the sweeper's lapse has exactly
     * one winner and the loser learns it from the row count.
     *
     * @param expectedVersion the version the caller read, or null when "still live and
     *                        unconfirmed" is the whole question (a round that attached
     *                        without touching the session's version)
     * @return whether the claim was confirmed by this call
     */
    public boolean confirmClaim(
            UUID tenantId, UUID sessionId, @Nullable Integer expectedVersion, String confirmedBy, Instant now) {
        return jdbc.sql("""
                UPDATE dinein.table_sessions
                   SET confirmed_at = :now,
                       confirmed_by = :confirmedBy,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL
                   AND (:expectedVersion::integer IS NULL OR version = :expectedVersion::integer)
                """)
                        .param("now", utc(now))
                        .param("confirmedBy", confirmedBy)
                        .param("tenantId", tenantId)
                        .param("id", sessionId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    // ----------------------------------------------------- walk-in claims (ADR 0143)

    /**
     * Locks tables for the rest of the transaction, in id order.
     *
     * <p>The one lock a guest opening a table and a host confirming or amending a
     * booking over it have in common, so the two serialize instead of both
     * succeeding on stale reads (ADR 0143, Decision 5). Id order, because two
     * transactions locking the same pair in opposite orders is a deadlock, and the
     * ordering column is the key, which an update never changes.
     *
     * @return the locked rows, in id order. A table that does not exist at this
     *         tenant is simply absent
     */
    public List<TableRow> lockTables(UUID tenantId, Collection<UUID> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_TABLE + """
                 WHERE tenant_id = :tenantId AND id IN (:ids)
                 ORDER BY id
                 FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("ids", tableIds)
                .query(JdbcDineInStore::mapTable)
                .list();
    }

    /**
     * Whether a CONFIRMED booking holds this table for any part of {@code [from, to)}.
     *
     * <p>The same interval the exclusion constraint keeps (reservation_tables.held_during,
     * which already includes the turnaround buffer), read the way the constraint
     * reads it. Only {@code CONFIRMED}: a {@code REQUESTED} booking holds nothing
     * yet, and a {@code SEATED} one has a session of its own that occupies the table
     * (ADR 0143, Decision 5).
     */
    public boolean tableHeldByConfirmedBooking(UUID tenantId, UUID tableId, Instant from, Instant to) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM dinein.reservation_tables rt
                     WHERE rt.tenant_id = :tenantId AND rt.table_id = :tableId
                       AND rt.status = 'CONFIRMED'
                       AND rt.held_during && tstzrange(:from::timestamptz, :to::timestamptz, '[)'))
                """)
                .param("tenantId", tenantId)
                .param("tableId", tableId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Boolean.class)
                .single());
    }

    /** Which of these tables a live session sits at right now. */
    public List<UUID> occupiedTables(UUID tenantId, Collection<UUID> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT table_id FROM dinein.session_tables
                 WHERE tenant_id = :tenantId AND table_id IN (:ids) AND left_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("ids", tableIds)
                .query((row, number) -> row.getObject("table_id", UUID.class))
                .list();
    }

    /** Live unconfirmed guest claims at a branch: the number the branch cap is measured against. */
    public int countLiveUnconfirmedClaims(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT count(*) FROM dinein.table_sessions
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(Integer.class)
                .single();
    }

    /** Whether this account already holds a live unconfirmed claim at this branch. */
    public boolean accountHasLiveUnconfirmedClaim(UUID tenantId, UUID locationId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM dinein.table_sessions
                     WHERE tenant_id = :tenantId AND location_id = :locationId
                       AND opened_by_account_id = :accountId
                       AND origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL)
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("accountId", accountId)
                .query(Boolean.class)
                .single());
    }

    /**
     * Claims this account opened at this branch since {@code since}, whatever became
     * of them. A count over {@code table_sessions} rather than a cache entry, so it
     * survives a restart and is the same number an operator can query.
     */
    public int countClaimsOpenedSince(UUID tenantId, UUID locationId, UUID accountId, Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM dinein.table_sessions
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND opened_by_account_id = :accountId
                   AND origin = 'GUEST_QR' AND opened_at >= :since
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("accountId", accountId)
                .param("since", utc(since))
                .query(Integer.class)
                .single();
    }

    /**
     * Every live unconfirmed guest claim whose window has passed, in any live status,
     * soonest first and across every tenant (ADR 0143, Decision 4).
     *
     * <p>The sweeper's one read. It selects on {@code closed_at IS NULL}, not on
     * {@code status = 'OPEN'}: a guest can move a session to {@code BILL_REQUESTED}
     * with the table's token alone, and a status-list selector would never see a claim
     * one tap had moved out of {@code OPEN}. Cross-tenant by design -- a sweep has no
     * tenant -- and safe for the reason the other platform sweepers' reads are: every
     * row it returns carries its own tenant, and everything the sweeper does next is
     * scoped by it.
     */
    public List<SessionRow> dueClaims(Instant now, int limit) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL
                   AND claim_expires_at <= :now
                 ORDER BY claim_expires_at
                 LIMIT :limit
                """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(JdbcDineInStore::mapSession)
                .list();
    }

    /** The live unconfirmed claims this account holds, at any branch of the tenant. */
    public List<SessionRow> liveUnconfirmedClaimsOf(UUID tenantId, UUID accountId) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE tenant_id = :tenantId AND opened_by_account_id = :accountId
                   AND origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcDineInStore::mapSession)
                .list();
    }

    /**
     * What {@code opened_by} says once the account it named has been erased. The guest
     * route writes {@code 'guest:<accountId>'} there as well as into
     * {@code opened_by_account_id}, and the column is NOT NULL, so it is neutralised
     * rather than cleared.
     */
    private static final String ERASED_OPENER = "guest:erased";

    /**
     * Clears a claimant's id from every guest-opened session of theirs that no longer
     * needs it (ADR 0015): the dedicated column, and the same id inside {@code opened_by}.
     */
    public int clearClaimant(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                UPDATE dinein.table_sessions
                   SET opened_by_account_id = NULL, opened_by = :erasedOpener, updated_at = now()
                 WHERE tenant_id = :tenantId AND opened_by_account_id = :accountId
                   AND (confirmed_at IS NOT NULL OR closed_at IS NOT NULL)
                """)
                .param("erasedOpener", ERASED_OPENER)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .update();
    }

    /** Every table this session has sat at, including any it has already left. */
    public List<UUID> tablesForSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                SELECT table_id FROM dinein.session_tables
                WHERE tenant_id = :tenantId AND session_id = :sessionId
                ORDER BY joined_at
                """)
                .param("tenantId", tenantId)
                .param("sessionId", sessionId)
                .query((row, number) -> row.getObject("table_id", UUID.class))
                .list();
    }

    /**
     * The tables behind each of a batch of orders, in one round trip
     * (ADR 0047, {@code OrderTablesPort}).
     *
     * <p>Order id to session to occupied tables to the table's own row. Every hop
     * carries the tenant -- the order id is a UUID a caller supplies, and matching
     * it alone would let a session of another tenant answer for it. Ordered by the
     * moment each table was joined, so a party pushed together reads in the order
     * the room was arranged ({@code TableSessionService.open} gives each table of
     * one request its own instant). The table code is only the last tie-break, so
     * two rows can never come back in a different order from one read to the next.
     */
    public List<OrderTableRow> tablesForOrders(UUID tenantId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT so.order_id, so.session_id, t.id AS table_id, t.code, t.display_name
                FROM dinein.session_orders so
                JOIN dinein.session_tables st
                  ON st.session_id = so.session_id AND st.tenant_id = so.tenant_id
                JOIN dinein.tables t
                  ON t.id = st.table_id AND t.tenant_id = st.tenant_id
                WHERE so.tenant_id = :tenantId AND so.order_id IN (:orderIds)
                ORDER BY so.order_id, st.joined_at, t.code
                """)
                .param("tenantId", tenantId)
                .param("orderIds", orderIds)
                .query((row, number) -> new OrderTableRow(
                        row.getObject("order_id", UUID.class),
                        row.getObject("session_id", UUID.class),
                        row.getObject("table_id", UUID.class),
                        row.getString("code"),
                        row.getString("display_name")))
                .list();
    }

    /** One (order, table) pair of {@link #tablesForOrders}. */
    public record OrderTableRow(UUID orderId, UUID sessionId, UUID tableId, String code, String displayName) {}

    /**
     * The tables behind each of a batch of sessions, in one round trip -- what the
     * live list and the New Order screen's table picker name a party by.
     *
     * <p>Every table the session has sat at, in the order it was joined (the same
     * order {@link #tablesForOrders} reads, so the picker and the ticket chip agree
     * on {@code T7 + T8}), with the table code as the last tie-break. The tenant is
     * on the session-table row and on the table row: a session id is a UUID a
     * caller supplies, and matching it alone would let another tenant's session
     * answer for it.
     */
    public List<SessionTableRow> tablesForSessions(UUID tenantId, Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT st.session_id, t.id AS table_id, t.code, t.display_name
                FROM dinein.session_tables st
                JOIN dinein.tables t
                  ON t.id = st.table_id AND t.tenant_id = st.tenant_id
                WHERE st.tenant_id = :tenantId AND st.session_id IN (:sessionIds)
                ORDER BY st.session_id, st.joined_at, t.code
                """)
                .param("tenantId", tenantId)
                .param("sessionIds", sessionIds)
                .query((row, number) -> new SessionTableRow(
                        row.getObject("session_id", UUID.class),
                        row.getObject("table_id", UUID.class),
                        row.getString("code"),
                        row.getString("display_name")))
                .list();
    }

    /** One (session, table) pair of {@link #tablesForSessions}. */
    public record SessionTableRow(UUID sessionId, UUID tableId, String code, String displayName) {}

    /**
     * The round an order already is, if it is one: which session's bill it is on and
     * at what sequence. Read after a duplicate-key refusal from {@link #addOrder} to
     * tell a retry of a write that landed (same session) from a second bill (another
     * session).
     */
    public Optional<RoundRow> findRoundOfOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT session_id, sequence FROM dinein.session_orders
                WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new RoundRow(row.getObject("session_id", UUID.class), row.getInt("sequence")))
                .optional();
    }

    /** A round's home: its session and its position on that bill. */
    public record RoundRow(UUID sessionId, int sequence) {}

    // ---------------------------------------------------------------- rounds

    /**
     * Adds a round to a session.
     *
     * <p>The sequence is allocated from this session's own rows inside the same
     * statement, so two waiters firing rounds at one table in the same second
     * settle on the unique index rather than on whichever SELECT ran first.
     */
    public int addOrder(UUID sessionId, UUID orderId, UUID tenantId, Instant now) {
        return jdbc.sql("""
                INSERT INTO dinein.session_orders (session_id, order_id, tenant_id, sequence, added_at)
                SELECT :sessionId::uuid, :orderId::uuid, :tenantId::uuid,
                       COALESCE(MAX(so.sequence), 0) + 1, :now::timestamptz
                FROM dinein.session_orders so
                WHERE so.session_id = :sessionId
                RETURNING sequence
                """)
                .param("sessionId", sessionId)
                .param("orderId", orderId)
                .param("tenantId", tenantId)
                .param("now", utc(now))
                .query((row, number) -> row.getInt("sequence"))
                .single();
    }

    /**
     * Moves a session's version because a round joined its bill, and returns the version it
     * now has.
     *
     * <p>The bill is the session's orders, so attaching one changes what a close settles. A
     * party someone read at version {@code n} and is then asked to close "as read" is no longer
     * the party that was read: {@code If-Match: n} has to stop matching, or the guard ADR 0031
     * puts on a close guards nothing. Taken inside the transaction that attached the round, on
     * the row {@link #lockSession} already holds, so a close racing the attach either loses
     * its conditional {@code UPDATE} (the version moved) or finds the session closed and the
     * attach is refused.
     */
    public int bumpVersionForRound(UUID tenantId, UUID sessionId, Instant now) {
        return jdbc.sql("""
                UPDATE dinein.table_sessions
                   SET version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                RETURNING version
                """)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .query((row, number) -> row.getInt("version"))
                .single();
    }

    public List<UUID> ordersInSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                SELECT order_id FROM dinein.session_orders
                WHERE tenant_id = :tenantId AND session_id = :sessionId
                ORDER BY sequence
                """)
                .param("tenantId", tenantId)
                .param("sessionId", sessionId)
                .query((row, number) -> row.getObject("order_id", UUID.class))
                .list();
    }

    // ------------------------------------------------------------- row types

    /**
     * @param walkIn          ADR 0143's per-branch switch and numbers. The defaults
     *                        are {@link WalkInPolicy#OFF}: a branch with no settings
     *                        row, or one that never turned the capability on, has it
     *                        off
     * @param sessionCurrency the currency a guest-opened session bills in (interim,
     *                        ADR 0055's single-currency pilot)
     */
    public record SettingsRow(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            QrMode qrMode,
            int turnaroundMinutes,
            int guestSessionTtlMinutes,
            int serviceChargeRateBp,
            int version,
            WalkInPolicy walkIn,
            String sessionCurrency) {}

    /**
     * ADR 0143's walk-in settings, with the record's own proposed defaults.
     *
     * @param selfSeat               whether a guest may open a claim at all
     * @param claimTtlMinutes        how long an unconfirmed claim holds a table (2..60)
     * @param horizonMinutes         how far ahead a CONFIRMED booking's hold blocks a
     *                               walk-in (0..480)
     * @param maxUnconfirmed         the branch-wide cap on live unconfirmed claims
     * @param dailyClaimsPerAccount  claims one account may open at one branch in 24h
     * @param paymentDeferMinutes    how long past its expiry a claim with a round in
     *                               flight is deferred
     */
    public record WalkInPolicy(
            boolean selfSeat,
            int claimTtlMinutes,
            int horizonMinutes,
            int maxUnconfirmed,
            int dailyClaimsPerAccount,
            int paymentDeferMinutes) {

        /** Off, with every number at the record's proposal. */
        public static final WalkInPolicy OFF = new WalkInPolicy(false, 15, 90, 5, 3, 30);
    }

    public record SectionRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String code,
            String displayName,
            int sortOrder,
            String status,
            int version) {}

    public record TableRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID sectionId,
            String code,
            String displayName,
            int seats,
            boolean joinable,
            @Nullable BigDecimal layoutX,
            @Nullable BigDecimal layoutY,
            String status,
            // Both null together and both set together: V0034's ck_table_qr_pair
            // makes a digest with no rotation instant, or the reverse, impossible
            // to write. A table nobody has printed a code for has neither.
            @Nullable String qrTokenHash,
            @Nullable Instant qrTokenRotatedAt,
            int version) {}

    public record GuestSessionRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID tableId,
            String tokenHash,
            QrMode qrMode,
            Instant issuedAt,
            Instant expiresAt,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason) {}

    public record ReservationRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            String guestNameEncrypted,
            String guestPhoneEncrypted,
            String guestPhoneLookupHash,
            @Nullable String secondaryPhoneEncrypted,
            @Nullable String noteEncrypted,
            int partySize,
            Instant requestedFrom,
            Instant requestedTo,
            int turnaroundMinutes,
            ReservationStatus status,
            UUID sourceChannelId,
            String createdBy,
            int version) {}

    public record SessionRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID reservationId,
            Integer partySize,
            LocalDate businessDate,
            String openedBy,
            Instant openedAt,
            SessionStatus status,
            Integer serviceChargeRateBpSnapshot,
            String currency,
            @Nullable Long settledTotalMinor,
            @Nullable Instant closedAt,
            @Nullable String closeReasonCode,
            int version,
            SessionOrigin origin,
            @Nullable UUID openedByAccountId,
            @Nullable Instant claimExpiresAt,
            @Nullable Instant confirmedAt,
            @Nullable String confirmedBy) {

        /**
         * Whether this is a guest's provisional claim that nobody has confirmed: the
         * one kind of session that lapses (ADR 0143). A confirmed claim, and every
         * staff session, is an ordinary session.
         */
        public boolean unconfirmedClaim() {
            return origin == SessionOrigin.GUEST_QR && confirmedAt == null;
        }
    }

    /**
     * One table's read of a requested window: whether it is booked, occupied,
     * both, or neither.
     *
     * @param booked   a confirmed or seated booking overlaps the asked-for window
     * @param occupied somebody is sitting there now, which is a different fact
     */
    public record AvailabilityRow(
            UUID tableId, String code, int seats, UUID sectionId, String status, boolean booked, boolean occupied) {}

    // --------------------------------------------------------------- mapping

    private static final String SETTINGS_COLUMNS = """
            tenant_id, brand_id, location_id, qr_mode, turnaround_minutes,
            guest_session_ttl_minutes, service_charge_rate_bp, version,
            walk_in_self_seat, walk_in_claim_ttl_minutes, walk_in_horizon_minutes,
            walk_in_max_unconfirmed, walk_in_daily_claims_per_account,
            walk_in_payment_defer_minutes, session_currency""";

    private static final String SELECT_TABLE = """
            SELECT id, tenant_id, brand_id, location_id, section_id, code, display_name,
                   seats, joinable, layout_x, layout_y, status,
                   qr_token_hash, qr_token_rotated_at, version
            FROM dinein.tables
            """;

    private static final String SELECT_RESERVATION = """
            SELECT id, tenant_id, brand_id, location_id, customer_account_id,
                   guest_name_encrypted, guest_phone_encrypted, guest_phone_lookup_hash,
                   secondary_phone_encrypted, note_encrypted, party_size,
                   requested_from, requested_to, turnaround_minutes_snapshot,
                   status, source_channel_id, created_by, version
            FROM dinein.reservations
            """;

    private static final String SELECT_SESSION = """
            SELECT id, tenant_id, brand_id, location_id, reservation_id, party_size,
                   business_date, opened_by, opened_at, status,
                   service_charge_rate_bp_snapshot, currency, settled_total_minor,
                   closed_at, close_reason_code, version,
                   origin, opened_by_account_id, claim_expires_at, confirmed_at, confirmed_by
            FROM dinein.table_sessions
            """;

    private static SettingsRow mapSettings(ResultSet row, int number) throws SQLException {
        return new SettingsRow(
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                QrMode.valueOf(row.getString("qr_mode")),
                row.getInt("turnaround_minutes"),
                row.getInt("guest_session_ttl_minutes"),
                row.getInt("service_charge_rate_bp"),
                row.getInt("version"),
                new WalkInPolicy(
                        row.getBoolean("walk_in_self_seat"),
                        row.getInt("walk_in_claim_ttl_minutes"),
                        row.getInt("walk_in_horizon_minutes"),
                        row.getInt("walk_in_max_unconfirmed"),
                        row.getInt("walk_in_daily_claims_per_account"),
                        row.getInt("walk_in_payment_defer_minutes")),
                row.getString("session_currency"));
    }

    private static SectionRow mapSection(ResultSet row, int number) throws SQLException {
        return new SectionRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getInt("sort_order"),
                row.getString("status"),
                row.getInt("version"));
    }

    private static TableRow mapTable(ResultSet row, int number) throws SQLException {
        return new TableRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("section_id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getInt("seats"),
                row.getBoolean("joinable"),
                row.getBigDecimal("layout_x"),
                row.getBigDecimal("layout_y"),
                row.getString("status"),
                row.getString("qr_token_hash"),
                nullableInstant(row, "qr_token_rotated_at"),
                row.getInt("version"));
    }

    private static GuestSessionRow mapGuestSession(ResultSet row, int number) throws SQLException {
        return new GuestSessionRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("table_id", UUID.class),
                row.getString("token_hash"),
                QrMode.valueOf(row.getString("qr_mode_snapshot")),
                instant(row, "issued_at"),
                instant(row, "expires_at"),
                nullableInstant(row, "revoked_at"),
                row.getString("revoked_reason"));
    }

    private static ReservationRow mapReservation(ResultSet row, int number) throws SQLException {
        return new ReservationRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("guest_name_encrypted"),
                row.getString("guest_phone_encrypted"),
                row.getString("guest_phone_lookup_hash"),
                row.getString("secondary_phone_encrypted"),
                row.getString("note_encrypted"),
                row.getInt("party_size"),
                instant(row, "requested_from"),
                instant(row, "requested_to"),
                row.getInt("turnaround_minutes_snapshot"),
                ReservationStatus.valueOf(row.getString("status")),
                row.getObject("source_channel_id", UUID.class),
                row.getString("created_by"),
                row.getInt("version"));
    }

    private static SessionRow mapSession(ResultSet row, int number) throws SQLException {
        return new SessionRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("reservation_id", UUID.class),
                // Nullable on purpose: a party size nobody asked for is not a party
                // of zero, and getInt would say it was.
                row.getObject("party_size", Integer.class),
                row.getObject("business_date", LocalDate.class),
                row.getString("opened_by"),
                instant(row, "opened_at"),
                SessionStatus.valueOf(row.getString("status")),
                row.getObject("service_charge_rate_bp_snapshot", Integer.class),
                row.getString("currency"),
                row.getObject("settled_total_minor", Long.class),
                nullableInstant(row, "closed_at"),
                row.getString("close_reason_code"),
                row.getInt("version"),
                SessionOrigin.valueOf(row.getString("origin")),
                row.getObject("opened_by_account_id", UUID.class),
                nullableInstant(row, "claim_expires_at"),
                nullableInstant(row, "confirmed_at"),
                row.getString("confirmed_by"));
    }

    /**
     * Reads a column the schema declares {@code NOT NULL}.
     *
     * <p>A null here is a fact this schema does not allow, so it fails at the row
     * that produced it rather than travelling on as an {@code Instant} typed to
     * promise it can't happen.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        return Objects.requireNonNull(nullableInstant(row, column), () -> column + " was NULL for a NOT NULL column");
    }

    private static @Nullable Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static @Nullable OffsetDateTime nullableUtc(@Nullable Instant instant) {
        return instant == null ? null : utc(instant);
    }
}
