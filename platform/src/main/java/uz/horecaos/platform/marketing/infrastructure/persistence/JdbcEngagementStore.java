package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.EngagementPolicy.EngagementOverride;

/**
 * Engagement policy, suppression, and the frequency-cap ledger (ADR 0044).
 *
 * <p>These three sit together because they are the three things that can refuse a
 * message for a reason that has nothing to do with the audience: the brand's own
 * quiet hours and cap, a standing suppression, and how much this customer has
 * already been sent this week.
 *
 * <p>The suppression read is deliberately broad and the write deliberately narrow.
 * {@link #hasActiveSuppression} matches a tenant-wide suppression as well as a
 * brand-scoped one, and a channel-neutral one as well as a channel-specific one,
 * because a customer who complained to a regulator did not complain about one
 * brand's newsletter. Nothing in this class deletes a suppression: a lift is an
 * UPDATE that leaves the row and names who lifted it.
 */
@Repository
public class JdbcEngagementStore {

    private final JdbcClient jdbc;

    public JdbcEngagementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- policy

    /**
     * The brand's effective policy: the platform default, tightened by any
     * override on record.
     *
     * <p>Resolved through {@link EngagementPolicy#tightenedBy}, so a row that
     * somehow loosens — written before the CHECK existed, or restored from an old
     * dump — is refused here rather than quietly obeyed.
     */
    public EngagementPolicy resolvePolicy(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT quiet_hours_start, quiet_hours_end, timezone,
                       marketing_messages_per_7d, marketing_messages_per_30d,
                       sms_price_per_segment_minor, currency
                  FROM marketing.engagement_policies
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((ResultSet row, int number) -> new EngagementOverride(
                        row.getObject("quiet_hours_start", LocalTime.class),
                        row.getObject("quiet_hours_end", LocalTime.class),
                        row.getString("timezone") == null ? null : ZoneId.of(row.getString("timezone")),
                        row.getObject("marketing_messages_per_7d", Integer.class),
                        row.getObject("marketing_messages_per_30d", Integer.class),
                        row.getObject("sms_price_per_segment_minor", Long.class),
                        row.getString("currency")))
                .optional()
                .map(EngagementPolicy.platformDefault()::tightenedBy)
                .orElseGet(EngagementPolicy::platformDefault);
    }

    /** Stores an override. The tighten-only rule is applied before this is called. */
    public void saveOverride(UUID tenantId, UUID brandId, EngagementOverride override, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("quietStart", override.quietHoursStart());
        parameters.put("quietEnd", override.quietHoursEnd());
        parameters.put(
                "timezone",
                override.timezone() == null
                        ? EngagementPolicy.DEFAULT_ZONE.getId()
                        : override.timezone().getId());
        parameters.put("weekly", override.messagesPer7Days());
        parameters.put("monthly", override.messagesPer30Days());
        parameters.put("price", override.smsPricePerSegmentMinor());
        parameters.put("currency", override.currency());
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO marketing.engagement_policies (
                    id, tenant_id, brand_id, quiet_hours_start, quiet_hours_end, timezone,
                    marketing_messages_per_7d, marketing_messages_per_30d,
                    sms_price_per_segment_minor, currency, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :quietStart, :quietEnd, :timezone,
                    :weekly, :monthly, :price, :currency, :now, :now)
                ON CONFLICT (tenant_id, brand_id) DO UPDATE SET
                    quiet_hours_start = EXCLUDED.quiet_hours_start,
                    quiet_hours_end = EXCLUDED.quiet_hours_end,
                    timezone = EXCLUDED.timezone,
                    marketing_messages_per_7d = EXCLUDED.marketing_messages_per_7d,
                    marketing_messages_per_30d = EXCLUDED.marketing_messages_per_30d,
                    sms_price_per_segment_minor = EXCLUDED.sms_price_per_segment_minor,
                    currency = EXCLUDED.currency,
                    version = marketing.engagement_policies.version + 1,
                    updated_at = EXCLUDED.updated_at
                """).params(parameters).update();
    }

    // ----------------------------------------------------------- suppression

    public UUID recordSuppression(
            UUID tenantId,
            @Nullable UUID brandId,
            UUID accountId,
            @Nullable String channel,
            String reason,
            @Nullable UUID appliedBy,
            String appliedByType,
            String statedReason,
            Instant appliedAt,
            @Nullable Instant expiresAt) {

        UUID id = UUID.randomUUID();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("accountId", accountId);
        parameters.put("channel", channel);
        parameters.put("reason", reason);
        parameters.put("appliedBy", appliedBy);
        parameters.put("appliedByType", appliedByType);
        parameters.put("statedReason", statedReason);
        parameters.put("appliedAt", utc(appliedAt));
        parameters.put("expiresAt", expiresAt == null ? null : utc(expiresAt));

        jdbc.sql("""
                INSERT INTO marketing.suppressions (
                    id, tenant_id, brand_id, customer_account_id, channel, reason,
                    applied_by, applied_by_type, stated_reason, applied_at, expires_at,
                    created_at)
                VALUES (:id, :tenantId, :brandId, :accountId, :channel, :reason,
                    :appliedBy, :appliedByType, :statedReason, :appliedAt, :expiresAt,
                    :appliedAt)
                """).params(parameters).update();
        return id;
    }

    /**
     * Whether anything currently stops this customer being messaged on this
     * channel.
     *
     * <p>A NULL brand or a NULL channel on the row widens it, which is why both
     * arms are written as "matches, or is unscoped". Writing them as equality
     * would let a tenant-wide {@code PLATFORM_BLOCK} be bypassed by naming a brand.
     */
    public boolean hasActiveSuppression(UUID tenantId, UUID brandId, UUID accountId, String channel, Instant now) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("accountId", accountId);
        parameters.put("channel", channel);
        parameters.put("now", utc(now));

        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                      FROM marketing.suppressions s
                     WHERE s.tenant_id = :tenantId
                       AND s.customer_account_id = :accountId
                       AND s.lifted_at IS NULL
                       AND (s.expires_at IS NULL OR s.expires_at > :now)
                       AND (s.brand_id IS NULL OR s.brand_id = :brandId)
                       AND (s.channel IS NULL OR s.channel = :channel))
                """).params(parameters).query(Boolean.class).single();
    }

    /** Closes a suppression without removing the evidence that it existed. */
    public boolean liftSuppression(UUID tenantId, UUID suppressionId, UUID liftedBy, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.suppressions
                   SET lifted_at = :now, lifted_by = :liftedBy, lift_reason = :reason
                 WHERE tenant_id = :tenantId AND id = :id AND lifted_at IS NULL
                """)
                        .param("tenantId", tenantId)
                        .param("id", suppressionId)
                        .param("liftedBy", liftedBy)
                        .param("reason", reason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    // -------------------------------------------------------- frequency cap

    /**
     * How many marketing messages this customer has had from this brand in the
     * window, across every channel.
     *
     * <p>Counted from the ledger rather than read from the projection's cached
     * columns. The cached ones are right for showing an approver a number and
     * wrong for deciding whether one more message is lawful: nothing decrements a
     * counter as a rolling window slides.
     */
    public int sendsWithin(UUID tenantId, UUID brandId, UUID accountId, Instant since) {
        return jdbc.sql("""
                SELECT COUNT(*)
                  FROM marketing.marketing_sends
                 WHERE tenant_id = :tenantId
                   AND brand_id = :brandId
                   AND customer_account_id = :accountId
                   AND sent_at >= :since
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .param("since", utc(since))
                .query(Integer.class)
                .single();
    }

    /**
     * Writes the ledger row for one message.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on {@code (source_id, customer_account_id)}.
     * A replayed batch must not inflate somebody's usage against the cap and
     * silence them for a week on the strength of a message they were only sent
     * once.
     *
     * @return true when this call wrote the row
     */
    public boolean recordSend(
            UUID tenantId,
            UUID brandId,
            UUID accountId,
            String channel,
            String sourceType,
            UUID sourceId,
            @Nullable UUID notificationId,
            Instant sentAt) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("accountId", accountId);
        parameters.put("channel", channel);
        parameters.put("sourceType", sourceType);
        parameters.put("sourceId", sourceId);
        parameters.put("notificationId", notificationId);
        parameters.put("sentAt", utc(sentAt));

        return jdbc.sql("""
                INSERT INTO marketing.marketing_sends (
                    id, tenant_id, brand_id, customer_account_id, channel,
                    source_type, source_id, notification_id, sent_at)
                VALUES (:id, :tenantId, :brandId, :accountId, :channel,
                    :sourceType, :sourceId, :notificationId, :sentAt)
                ON CONFLICT (source_id, customer_account_id) DO NOTHING
                """).params(parameters).update() == 1;
    }

    private static final String SUPPRESSION_COLUMNS = """
            id, brand_id, customer_account_id, channel, reason, applied_by_type,
            stated_reason, applied_at, expires_at, lifted_at
            """;

    public Optional<SuppressionRow> findSuppression(UUID tenantId, UUID suppressionId) {
        return jdbc.sql("SELECT " + SUPPRESSION_COLUMNS
                        + " FROM marketing.suppressions WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", suppressionId)
                .query(JdbcEngagementStore::suppressionRow)
                .optional();
    }

    /**
     * A brand's suppressions, newest first — brand-scoped rows and the
     * tenant-wide ones (a null {@code brand_id}) together, the same "matches, or
     * is unscoped" widening {@link #hasActiveSuppression} applies, because a
     * customer who complained to a regulator did not complain about one brand's
     * newsletter.
     *
     * @param activeOnly when true, excludes anything already lifted or expired
     */
    public List<SuppressionRow> listByBrand(UUID tenantId, UUID brandId, boolean activeOnly, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("now", utc(now));

        String activeFilter = activeOnly ? " AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > :now)" : "";

        return jdbc.sql("SELECT " + SUPPRESSION_COLUMNS + " FROM marketing.suppressions"
                        + " WHERE tenant_id = :tenantId AND (brand_id = :brandId OR brand_id IS NULL)"
                        + activeFilter
                        + " ORDER BY applied_at DESC")
                .params(parameters)
                .query(JdbcEngagementStore::suppressionRow)
                .list();
    }

    private static SuppressionRow suppressionRow(ResultSet row, int number) throws SQLException {
        return new SuppressionRow(
                row.getObject("id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("channel"),
                row.getString("reason"),
                row.getString("applied_by_type"),
                row.getString("stated_reason"),
                // applied_at is NOT NULL DEFAULT now() (V0043), unlike
                // expires_at and lifted_at below, so it is read directly
                // rather than through the null-forwarding instant() helper.
                row.getObject("applied_at", OffsetDateTime.class).toInstant(),
                instant(row.getObject("expires_at", OffsetDateTime.class)),
                instant(row.getObject("lifted_at", OffsetDateTime.class)));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record SuppressionRow(
            UUID id,
            @Nullable UUID brandId,
            UUID customerAccountId,
            @Nullable String channel,
            String reason,
            String appliedByType,
            @Nullable String statedReason,
            Instant appliedAt,
            @Nullable Instant expiresAt,
            @Nullable Instant liftedAt) {}
}
