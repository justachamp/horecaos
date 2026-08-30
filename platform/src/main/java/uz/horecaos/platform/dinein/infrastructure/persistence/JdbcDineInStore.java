package uz.horecaos.platform.dinein.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
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

    private final JdbcClient jdbc;

    public JdbcDineInStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------- settings

    public Optional<SettingsRow> findSettings(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT tenant_id, brand_id, location_id, qr_mode, turnaround_minutes,
                       guest_session_ttl_minutes, service_charge_rate_bp, version
                FROM dinein.location_settings
                WHERE tenant_id = :tenantId AND location_id = :locationId
                """)
                .param("tenantId", tenantId).param("locationId", locationId)
                .query(JdbcDineInStore::mapSettings)
                .optional();
    }

    /**
     * Writes the branch's dine-in settings, creating the row on first use.
     *
     * <p>An upsert rather than a create-then-update pair because a branch has
     * exactly one of these and there is no meaningful difference between
     * configuring it and reconfiguring it. The version still moves, so ADR 0031's
     * expected-version check has something to compare.
     */
    public SettingsRow upsertSettings(SettingsRow settings, Instant now) {
        jdbc.sql("""
                INSERT INTO dinein.location_settings (
                    tenant_id, brand_id, location_id, qr_mode, turnaround_minutes,
                    guest_session_ttl_minutes, service_charge_rate_bp,
                    version, created_at, updated_at)
                VALUES (:tenantId, :brandId, :locationId, :qrMode, :turnaround,
                    :ttl, :serviceCharge, 1, :now, :now)
                ON CONFLICT (location_id) DO UPDATE SET
                    qr_mode = EXCLUDED.qr_mode,
                    turnaround_minutes = EXCLUDED.turnaround_minutes,
                    guest_session_ttl_minutes = EXCLUDED.guest_session_ttl_minutes,
                    service_charge_rate_bp = EXCLUDED.service_charge_rate_bp,
                    version = dinein.location_settings.version + 1,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", settings.tenantId()).param("brandId", settings.brandId())
                .param("locationId", settings.locationId())
                .param("qrMode", settings.qrMode().name())
                .param("turnaround", settings.turnaroundMinutes())
                .param("ttl", settings.guestSessionTtlMinutes())
                .param("serviceCharge", settings.serviceChargeRateBp())
                .param("now", utc(now))
                .update();

        return findSettings(settings.tenantId(), settings.locationId()).orElseThrow();
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
                .param("tenantId", tenantId).param("locationId", locationId)
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
                .param("id", section.id()).param("tenantId", section.tenantId())
                .param("brandId", section.brandId()).param("locationId", section.locationId())
                .param("code", section.code()).param("displayName", section.displayName())
                .param("sortOrder", section.sortOrder()).param("status", section.status())
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
                .param("tenantId", tenantId).param("locationId", locationId)
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
                """)
                .params(params)
                .update();
    }

    public Optional<TableRow> findTable(UUID tenantId, UUID tableId) {
        return jdbc.sql(SELECT_TABLE + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", tableId)
                .query(JdbcDineInStore::mapTable)
                .optional();
    }

    public List<TableRow> listTables(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_TABLE + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY section_id, code
                """)
                .param("tenantId", tenantId).param("locationId", locationId)
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
    public boolean rotateQrToken(UUID tenantId, UUID tableId, int expectedVersion,
            String tokenHash, Instant now) {

        return jdbc.sql("""
                UPDATE dinein.tables
                   SET qr_token_hash = :hash,
                       qr_token_rotated_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                   AND status <> 'ARCHIVED'
                """)
                .param("hash", tokenHash).param("now", utc(now))
                .param("tenantId", tenantId).param("id", tableId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public boolean updateTableStatus(UUID tenantId, UUID tableId, int expectedVersion,
            String status, Instant now) {

        return jdbc.sql("""
                UPDATE dinein.tables
                   SET status = :status, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                .param("status", status).param("now", utc(now))
                .param("tenantId", tenantId).param("id", tableId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
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
                .param("id", guest.id()).param("tenantId", guest.tenantId())
                .param("brandId", guest.brandId()).param("locationId", guest.locationId())
                .param("tableId", guest.tableId()).param("hash", guest.tokenHash())
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
                .param("hash", tokenHash).param("now", utc(now))
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
                .param("now", utc(now)).param("reason", reason)
                .param("tenantId", tenantId).param("tableId", tableId)
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
                """)
                .params(params)
                .update();
    }

    /**
     * Attaches the tables a booking wants, with the effective hold each one takes.
     *
     * <p>The copied status is not supplied: V0034's insert trigger takes it from
     * the parent booking, so no caller can write a hold whose status disagrees
     * with the booking it belongs to.
     */
    public void insertReservationTable(UUID reservationId, UUID tableId, UUID tenantId,
            UUID locationId, Instant heldFrom, Instant heldTo) {

        jdbc.sql("""
                INSERT INTO dinein.reservation_tables (
                    reservation_id, table_id, tenant_id, location_id, held_during, status)
                VALUES (:reservationId, :tableId, :tenantId, :locationId,
                    tstzrange(:heldFrom::timestamptz, :heldTo::timestamptz, '[)'), 'REQUESTED')
                """)
                .param("reservationId", reservationId).param("tableId", tableId)
                .param("tenantId", tenantId).param("locationId", locationId)
                .param("heldFrom", utc(heldFrom)).param("heldTo", utc(heldTo))
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
                .param("heldFrom", utc(heldFrom)).param("heldTo", utc(heldTo))
                .param("tenantId", tenantId).param("reservationId", reservationId)
                .update();
    }

    public Optional<ReservationRow> findReservation(UUID tenantId, UUID reservationId) {
        return jdbc.sql(SELECT_RESERVATION + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", reservationId)
                .query(JdbcDineInStore::mapReservation)
                .optional();
    }

    public List<UUID> tablesForReservation(UUID tenantId, UUID reservationId) {
        return jdbc.sql("""
                SELECT table_id FROM dinein.reservation_tables
                WHERE tenant_id = :tenantId AND reservation_id = :reservationId
                ORDER BY table_id
                """)
                .param("tenantId", tenantId).param("reservationId", reservationId)
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
    public boolean moveReservation(UUID tenantId, UUID reservationId, ReservationStatus from,
            ReservationStatus to, int expectedVersion, Instant now) {

        return jdbc.sql("""
                UPDATE dinein.reservations
                   SET status = :to, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = :from AND version = :expectedVersion
                """)
                .param("to", to.name()).param("from", from.name())
                .param("now", utc(now)).param("tenantId", tenantId).param("id", reservationId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
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
    public List<AvailabilityRow> tableAvailability(UUID tenantId, UUID locationId,
            Instant from, Instant to) {

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
                .param("tenantId", tenantId).param("locationId", locationId)
                .param("from", utc(from)).param("to", utc(to))
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
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO dinein.table_sessions (
                    id, tenant_id, brand_id, location_id, reservation_id, party_size,
                    business_date, opened_by, opened_at, status,
                    service_charge_rate_bp_snapshot, currency, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :reservationId, :partySize,
                    :businessDate, :openedBy, :openedAt, :status,
                    :serviceCharge, :currency, 1, :now, :now)
                """)
                .params(params)
                .update();
    }

    public void occupyTable(UUID sessionId, UUID tableId, UUID tenantId, UUID locationId,
            Instant joinedAt) {

        jdbc.sql("""
                INSERT INTO dinein.session_tables (
                    session_id, table_id, tenant_id, location_id, joined_at)
                VALUES (:sessionId, :tableId, :tenantId, :locationId, :joinedAt)
                """)
                .param("sessionId", sessionId).param("tableId", tableId)
                .param("tenantId", tenantId).param("locationId", locationId)
                .param("joinedAt", utc(joinedAt))
                .update();
    }

    public Optional<SessionRow> findSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql(SELECT_SESSION + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", sessionId)
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
                .param("tenantId", tenantId).param("tableId", tableId)
                .query(JdbcDineInStore::mapSession)
                .optional();
    }

    public List<SessionRow> listLiveSessions(UUID tenantId, UUID locationId) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND status IN ('OPEN', 'BILL_REQUESTED', 'SETTLING')
                 ORDER BY opened_at
                """)
                .param("tenantId", tenantId).param("locationId", locationId)
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
    public boolean moveSession(UUID tenantId, UUID sessionId, SessionStatus from,
            SessionStatus to, int expectedVersion, Instant closedAt, Long settledTotalMinor,
            String closeReasonCode, Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", sessionId);
        params.put("from", from.name());
        params.put("to", to.name());
        params.put("expectedVersion", expectedVersion);
        params.put("closedAt", nullableUtc(closedAt));
        params.put("settledTotal", settledTotalMinor);
        params.put("closeReason", closeReasonCode);
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE dinein.table_sessions
                   SET status = :to,
                       closed_at = :closedAt,
                       settled_total_minor = COALESCE(:settledTotal::bigint, settled_total_minor),
                       close_reason_code = COALESCE(:closeReason::varchar, close_reason_code),
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = :from AND version = :expectedVersion
                """)
                .params(params)
                .update() == 1;
    }

    /** Every table this session has sat at, including any it has already left. */
    public List<UUID> tablesForSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                SELECT table_id FROM dinein.session_tables
                WHERE tenant_id = :tenantId AND session_id = :sessionId
                ORDER BY joined_at
                """)
                .param("tenantId", tenantId).param("sessionId", sessionId)
                .query((row, number) -> row.getObject("table_id", UUID.class))
                .list();
    }

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
                .param("sessionId", sessionId).param("orderId", orderId)
                .param("tenantId", tenantId).param("now", utc(now))
                .query((row, number) -> row.getInt("sequence"))
                .single();
    }

    public List<UUID> ordersInSession(UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                SELECT order_id FROM dinein.session_orders
                WHERE tenant_id = :tenantId AND session_id = :sessionId
                ORDER BY sequence
                """)
                .param("tenantId", tenantId).param("sessionId", sessionId)
                .query((row, number) -> row.getObject("order_id", UUID.class))
                .list();
    }

    // ------------------------------------------------------------- row types

    public record SettingsRow(UUID tenantId, UUID brandId, UUID locationId, QrMode qrMode,
            int turnaroundMinutes, int guestSessionTtlMinutes, int serviceChargeRateBp,
            int version) {
    }

    public record SectionRow(UUID id, UUID tenantId, UUID brandId, UUID locationId, String code,
            String displayName, int sortOrder, String status, int version) {
    }

    public record TableRow(UUID id, UUID tenantId, UUID brandId, UUID locationId, UUID sectionId,
            String code, String displayName, int seats, boolean joinable, BigDecimal layoutX,
            BigDecimal layoutY, String status, String qrTokenHash, Instant qrTokenRotatedAt,
            int version) {
    }

    public record GuestSessionRow(UUID id, UUID tenantId, UUID brandId, UUID locationId,
            UUID tableId, String tokenHash, QrMode qrMode, Instant issuedAt, Instant expiresAt,
            Instant revokedAt, String revokedReason) {
    }

    public record ReservationRow(UUID id, UUID tenantId, UUID brandId, UUID locationId,
            UUID customerAccountId, String guestNameEncrypted, String guestPhoneEncrypted,
            String guestPhoneLookupHash, String secondaryPhoneEncrypted, String noteEncrypted,
            int partySize, Instant requestedFrom, Instant requestedTo, int turnaroundMinutes,
            ReservationStatus status, UUID sourceChannelId, String createdBy, int version) {
    }

    public record SessionRow(UUID id, UUID tenantId, UUID brandId, UUID locationId,
            UUID reservationId, Integer partySize, LocalDate businessDate, String openedBy,
            Instant openedAt, SessionStatus status, Integer serviceChargeRateBpSnapshot,
            String currency, Long settledTotalMinor, Instant closedAt, String closeReasonCode,
            int version) {
    }

    /**
     * @param booked   a confirmed or seated booking overlaps the asked-for window
     * @param occupied somebody is sitting there now, which is a different fact
     */
    public record AvailabilityRow(UUID tableId, String code, int seats, UUID sectionId,
            String status, boolean booked, boolean occupied) {
    }

    // --------------------------------------------------------------- mapping

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
                   closed_at, close_reason_code, version
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
                row.getInt("version"));
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
                instant(row, "qr_token_rotated_at"),
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
                instant(row, "revoked_at"),
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
                instant(row, "closed_at"),
                row.getString("close_reason_code"),
                row.getInt("version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableUtc(Instant instant) {
        return instant == null ? null : utc(instant);
    }
}
