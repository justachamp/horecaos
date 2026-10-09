package uz.horecaos.platform.customers.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lead persistence (ADR 0111).
 *
 * <p>Every statement names the tenant, and a read for a branch names the branch in the predicate
 * rather than filtering afterwards: a location holder of {@code customer.lead.read} sees the leads
 * assigned to her branch, and the query is written so that a sibling's lead is not a row it could
 * return. Nothing here decrypts: the columns come back as stored, and the service that holds the
 * protection decides who may see what.
 */
@Repository
public class JdbcLeadStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, status, source, phone_lookup_hash, phone_encrypted, phone_masked,
            display_name_encrypted, notes_encrypted, customer_account_id, assigned_location_id, assigned_at,
            callback_due_at, origin_campaign_id, origin_step_sequence, converted_order_id,
            converted_reservation_id, closed_reason, created_by, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public JdbcLeadStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new lead, status {@code NEW}, version 1. */
    public void insert(NewLead lead, Instant now) {
        insertStatement(lead, now, "");
    }

    /**
     * A campaign scenario's lead, at most once per (campaign, step, number).
     *
     * @return false when the same step already produced a lead for this number
     */
    public boolean insertScenarioOnce(NewLead lead, Instant now) {
        return insertStatement(
                        lead,
                        now,
                        "ON CONFLICT (tenant_id, origin_campaign_id, origin_step_sequence, phone_lookup_hash) "
                                + "WHERE source = 'CAMPAIGN_SCENARIO' DO NOTHING")
                == 1;
    }

    private int insertStatement(NewLead lead, Instant now, String conflict) {
        return jdbc.sql("""
                INSERT INTO customer.leads (
                    id, tenant_id, brand_id, status, source, phone_lookup_hash, phone_encrypted, phone_masked,
                    display_name_encrypted, notes_encrypted, customer_account_id, assigned_location_id,
                    assigned_at, callback_due_at, origin_campaign_id, origin_step_sequence, created_by,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, 'NEW', :source, :hash, :phone, :masked,
                        :name, :notes, :accountId, :locationId,
                        :assignedAt, NULL, :campaignId, :stepSequence, :createdBy,
                        1, :now, :now)
                """ + conflict)
                .param("id", lead.id())
                .param("tenantId", lead.tenantId())
                .param("brandId", lead.brandId())
                .param("source", lead.source())
                .param("hash", lead.phoneLookupHash())
                .param("phone", lead.phoneEncrypted())
                .param("masked", lead.phoneMasked())
                .param("name", lead.displayNameEncrypted())
                .param("notes", lead.notesEncrypted())
                .param("accountId", lead.customerAccountId())
                .param("locationId", lead.assignedLocationId())
                .param("assignedAt", lead.assignedLocationId() == null ? null : at(now))
                .param("campaignId", lead.originCampaignId())
                .param("stepSequence", lead.originStepSequence())
                .param("createdBy", lead.createdBy())
                .param("now", at(now))
                .update();
    }

    public Optional<LeadRow> find(UUID tenantId, UUID leadId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM customer.leads WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", leadId)
                .query(JdbcLeadStore::toRow)
                .optional();
    }

    /** The existing lead a scenario step already produced for this number. */
    public Optional<UUID> findScenarioLead(UUID tenantId, UUID campaignId, int stepSequence, String phoneLookupHash) {
        return jdbc.sql("""
                SELECT id FROM customer.leads
                 WHERE tenant_id = :tenantId AND source = 'CAMPAIGN_SCENARIO'
                   AND origin_campaign_id = :campaignId AND origin_step_sequence = :step
                   AND phone_lookup_hash = :hash
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .param("step", stepSequence)
                .param("hash", phoneLookupHash)
                .query(UUID.class)
                .optional();
    }

    /**
     * Writes the fields a transition or a hand-off may change, if the lead is still at the version
     * the caller read.
     *
     * @return false on a stale version (or a vanished row): the caller reports a conflict
     */
    public boolean save(LeadRow lead, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE customer.leads
                   SET status = :status, assigned_location_id = :locationId, assigned_at = :assignedAt,
                       callback_due_at = :callbackDueAt, converted_order_id = :orderId,
                       converted_reservation_id = :reservationId, closed_reason = :closedReason,
                       customer_account_id = :accountId,
                       version = version + 1, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId AND version = :expected
                """)
                        .param("status", lead.status())
                        .param("locationId", lead.assignedLocationId())
                        .param("assignedAt", lead.assignedAt() == null ? null : at(lead.assignedAt()))
                        .param("callbackDueAt", lead.callbackDueAt() == null ? null : at(lead.callbackDueAt()))
                        .param("orderId", lead.convertedOrderId())
                        .param("reservationId", lead.convertedReservationId())
                        .param("closedReason", lead.closedReason())
                        .param("accountId", lead.customerAccountId())
                        .param("now", at(now))
                        .param("id", lead.id())
                        .param("tenantId", lead.tenantId())
                        .param("expected", expectedVersion)
                        .update()
                == 1;
    }

    /**
     * The queue, newest first.
     *
     * @param filter    what to narrow it to; {@link Filter#none()} for everything the reach allows
     * @param cursor    the last row of the previous page, or null for the first
     * @param limit     at most this many rows
     */
    public List<LeadRow> list(
            UUID tenantId, Reach reach, Filter filter, @Nullable Cursor cursor, int limit, Instant now) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM customer.leads WHERE tenant_id = :tenantId");
        List<Object[]> params = new ArrayList<>();
        if (reach.brandId() != null) {
            sql.append(" AND brand_id = :reachBrand");
            params.add(new Object[] {"reachBrand", reach.brandId()});
        }
        if (reach.locationId() != null) {
            sql.append(" AND assigned_location_id = :reachLocation");
            params.add(new Object[] {"reachLocation", reach.locationId()});
        }
        if (!filter.statuses().isEmpty()) {
            sql.append(" AND status IN (:statuses)");
            params.add(new Object[] {"statuses", filter.statuses()});
        }
        if (filter.source() != null) {
            sql.append(" AND source = :source");
            params.add(new Object[] {"source", filter.source()});
        }
        if (filter.assignedLocationId() != null) {
            sql.append(" AND assigned_location_id = :assignedLocation");
            params.add(new Object[] {"assignedLocation", filter.assignedLocationId()});
        }
        if (filter.unassignedOnly()) {
            sql.append(" AND assigned_location_id IS NULL");
        }
        if (filter.customerAccountId() != null) {
            sql.append(" AND customer_account_id = :accountId");
            params.add(new Object[] {"accountId", filter.customerAccountId()});
        }
        if (filter.attentionWindow() != null) {
            // A new lead nobody has touched, and a scheduled callback that is due or about to be.
            sql.append(
                    " AND (status = 'NEW' OR (status = 'CALLBACK_SCHEDULED' AND callback_due_at <= :attentionUntil))");
            params.add(new Object[] {"attentionUntil", at(now.plus(filter.attentionWindow()))});
        }
        if (cursor != null) {
            sql.append(" AND (created_at, id) < (:cursorAt, :cursorId)");
            params.add(new Object[] {"cursorAt", at(cursor.createdAt())});
            params.add(new Object[] {"cursorId", cursor.id()});
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");

        JdbcClient.StatementSpec statement =
                jdbc.sql(sql.toString()).param("tenantId", tenantId).param("limit", limit);
        for (Object[] pair : params) {
            statement = statement.param((String) pair[0], pair[1]);
        }
        return statement.query(JdbcLeadStore::toRow).list();
    }

    /** Other open leads holding the same number, as a hint for an operator and never a merge. */
    public List<UUID> openLeadsWithPhone(UUID tenantId, String phoneLookupHash, UUID excludingLeadId) {
        return jdbc.sql("""
                SELECT id FROM customer.leads
                 WHERE tenant_id = :tenantId AND phone_lookup_hash = :hash AND id <> :excluding
                   AND status IN ('NEW', 'CONTACTED', 'CALLBACK_SCHEDULED')
                 ORDER BY created_at DESC LIMIT 10
                """)
                .param("tenantId", tenantId)
                .param("hash", phoneLookupHash)
                .param("excluding", excludingLeadId)
                .query(UUID.class)
                .list();
    }

    /** Leads linked to one account, newest first, for the customer card. */
    public List<LeadRow> forAccount(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM customer.leads
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC, id DESC LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query(JdbcLeadStore::toRow)
                .list();
    }

    /** The brand is this tenant's. */
    public boolean brandExists(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT count(*) FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId")
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** The branches of this brand, to check a hand-off names one that exists. */
    public boolean locationBelongsToBrand(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                SELECT count(*) FROM tenant.locations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :locationId
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("locationId", locationId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Overwrites what identifies the guest on every lead of an erased account (ADR 0029, ADR 0015):
     * the ciphertext is replaced, the hash is a fresh random value so erased rows do not all collide
     * on one, and the name and notes are dropped. The lead's own facts -- its status, its source, its
     * branch -- stay: they say nothing about who the guest was.
     *
     * <p>Two kinds of lead are the account's. A lead an operator <em>linked</em> to it is reached by
     * the link. A lead nobody linked is reached by the number: it holds one of the numbers the
     * account held ({@code phoneLookupHashes}, read before the account's own were overwritten),
     * which is her personal data wherever it was written down. A lead linked to a <em>different</em>
     * account is that account's whatever number it holds, and is left alone.
     *
     * @return how many leads were overwritten
     */
    public int erasePersonalFields(
            UUID tenantId,
            UUID accountId,
            Collection<String> phoneLookupHashes,
            java.util.function.Function<UUID, String> tombstoneFor,
            String tombstoneMasked,
            Instant now) {
        String byNumber = phoneLookupHashes.isEmpty()
                ? ""
                : " OR (customer_account_id IS NULL AND phone_lookup_hash IN (:hashes))";
        var select = jdbc.sql("SELECT id FROM customer.leads WHERE tenant_id = :tenantId "
                        + "AND (customer_account_id = :accountId" + byNumber + ")")
                .param("tenantId", tenantId)
                .param("accountId", accountId);
        if (!phoneLookupHashes.isEmpty()) {
            select = select.param("hashes", phoneLookupHashes);
        }
        List<UUID> ids = select.query(UUID.class).list();
        for (UUID id : ids) {
            jdbc.sql("""
                    UPDATE customer.leads
                       SET phone_encrypted = :encrypted, phone_masked = :masked, phone_lookup_hash = :hash,
                           display_name_encrypted = NULL, notes_encrypted = NULL, updated_at = :now
                     WHERE tenant_id = :tenantId AND id = :id
                    """)
                    .param("encrypted", tombstoneFor.apply(id))
                    .param("masked", tombstoneMasked)
                    .param("hash", UUID.randomUUID().toString())
                    .param("now", at(now))
                    .param("tenantId", tenantId)
                    .param("id", id)
                    .update();
        }
        return ids.size();
    }

    private static LeadRow toRow(ResultSet rs, int row) throws SQLException {
        return new LeadRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getString("status"),
                rs.getString("source"),
                rs.getString("phone_lookup_hash"),
                rs.getString("phone_encrypted"),
                rs.getString("phone_masked"),
                rs.getString("display_name_encrypted"),
                rs.getString("notes_encrypted"),
                rs.getObject("customer_account_id", UUID.class),
                rs.getObject("assigned_location_id", UUID.class),
                instant(rs, "assigned_at"),
                instant(rs, "callback_due_at"),
                rs.getObject("origin_campaign_id", UUID.class),
                (Integer) rs.getObject("origin_step_sequence"),
                rs.getObject("converted_order_id", UUID.class),
                rs.getObject("converted_reservation_id", UUID.class),
                rs.getString("closed_reason"),
                rs.getString("created_by"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** How far a reader's grant reaches: the whole tenant, one brand, or one branch's assigned leads. */
    public record Reach(@Nullable UUID brandId, @Nullable UUID locationId) {

        public static Reach tenant() {
            return new Reach(null, null);
        }

        public static Reach brand(UUID brandId) {
            return new Reach(brandId, null);
        }

        public static Reach location(UUID brandId, UUID locationId) {
            return new Reach(brandId, locationId);
        }

        /** Whether a lead lies inside this reach. */
        public boolean admits(LeadRow lead) {
            return (brandId == null || brandId.equals(lead.brandId()))
                    && (locationId == null || locationId.equals(lead.assignedLocationId()));
        }
    }

    /** What the queue is narrowed to. */
    public record Filter(
            Collection<String> statuses,
            @Nullable String source,
            @Nullable UUID assignedLocationId,
            boolean unassignedOnly,
            @Nullable UUID customerAccountId,
            java.time.@Nullable Duration attentionWindow) {

        public static Filter none() {
            return new Filter(List.of(), null, null, false, null, null);
        }
    }

    /** The last row of a page, which the next page continues from. */
    public record Cursor(Instant createdAt, UUID id) {}

    /** A lead about to be created. */
    public record NewLead(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String source,
            String phoneLookupHash,
            String phoneEncrypted,
            String phoneMasked,
            @Nullable String displayNameEncrypted,
            @Nullable String notesEncrypted,
            @Nullable UUID customerAccountId,
            @Nullable UUID assignedLocationId,
            @Nullable UUID originCampaignId,
            @Nullable Integer originStepSequence,
            String createdBy) {}

    /** One stored lead, as it is: ciphertext stays ciphertext. */
    public record LeadRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String status,
            String source,
            String phoneLookupHash,
            String phoneEncrypted,
            String phoneMasked,
            @Nullable String displayNameEncrypted,
            @Nullable String notesEncrypted,
            @Nullable UUID customerAccountId,
            @Nullable UUID assignedLocationId,
            @Nullable Instant assignedAt,
            @Nullable Instant callbackDueAt,
            @Nullable UUID originCampaignId,
            @Nullable Integer originStepSequence,
            @Nullable UUID convertedOrderId,
            @Nullable UUID convertedReservationId,
            @Nullable String closedReason,
            String createdBy,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        public LeadRow withStatus(String status) {
            return new LeadRow(
                    id,
                    tenantId,
                    brandId,
                    status,
                    source,
                    phoneLookupHash,
                    phoneEncrypted,
                    phoneMasked,
                    displayNameEncrypted,
                    notesEncrypted,
                    customerAccountId,
                    assignedLocationId,
                    assignedAt,
                    callbackDueAt,
                    originCampaignId,
                    originStepSequence,
                    convertedOrderId,
                    convertedReservationId,
                    closedReason,
                    createdBy,
                    version,
                    createdAt,
                    updatedAt);
        }

        /** The same lead, linked to the account an operator identified it as (ADR 0111 §4). */
        public LeadRow withAccount(UUID accountId) {
            return new LeadRow(
                    id,
                    tenantId,
                    brandId,
                    status,
                    source,
                    phoneLookupHash,
                    phoneEncrypted,
                    phoneMasked,
                    displayNameEncrypted,
                    notesEncrypted,
                    accountId,
                    assignedLocationId,
                    assignedAt,
                    callbackDueAt,
                    originCampaignId,
                    originStepSequence,
                    convertedOrderId,
                    convertedReservationId,
                    closedReason,
                    createdBy,
                    version,
                    createdAt,
                    updatedAt);
        }

        public LeadRow withHandOff(@Nullable UUID locationId, @Nullable Instant assignedAtValue) {
            return new LeadRow(
                    id,
                    tenantId,
                    brandId,
                    status,
                    source,
                    phoneLookupHash,
                    phoneEncrypted,
                    phoneMasked,
                    displayNameEncrypted,
                    notesEncrypted,
                    customerAccountId,
                    locationId,
                    assignedAtValue,
                    callbackDueAt,
                    originCampaignId,
                    originStepSequence,
                    convertedOrderId,
                    convertedReservationId,
                    closedReason,
                    createdBy,
                    version,
                    createdAt,
                    updatedAt);
        }

        public LeadRow withOutcome(
                String status,
                @Nullable Instant callbackDue,
                @Nullable UUID orderId,
                @Nullable UUID reservationId,
                @Nullable String reason) {
            return new LeadRow(
                    id,
                    tenantId,
                    brandId,
                    status,
                    source,
                    phoneLookupHash,
                    phoneEncrypted,
                    phoneMasked,
                    displayNameEncrypted,
                    notesEncrypted,
                    customerAccountId,
                    assignedLocationId,
                    assignedAt,
                    callbackDue,
                    originCampaignId,
                    originStepSequence,
                    orderId,
                    reservationId,
                    reason,
                    createdBy,
                    version,
                    createdAt,
                    updatedAt);
        }
    }
}
