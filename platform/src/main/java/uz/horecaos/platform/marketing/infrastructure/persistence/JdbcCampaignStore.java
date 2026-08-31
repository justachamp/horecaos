package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.RefusalReason;

/**
 * Campaigns, their batch claims, and their per-recipient receipts (ADR 0044).
 *
 * <p>{@link #claimBatch} is the important statement in this file, and the only one
 * whose exact shape matters. The cost ceiling and the recipient cap are enforced
 * by a single conditional UPDATE that both checks and reserves, so two workers
 * racing produce one winner and one zero-row update. Summing the sent rows and
 * comparing was the alternative, and it is how two workers both read the same
 * total, both conclude there is budget left, and both spend it.
 *
 * <p>The idempotency of expansion is the batch row's primary key. A replayed batch
 * inserts nothing, claims no reservation, and produces no second message.
 */
@Repository
public class JdbcCampaignStore {

    private final JdbcClient jdbc;

    public JdbcCampaignStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCampaign(NewCampaign campaign) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", campaign.id());
        parameters.put("tenantId", campaign.tenantId());
        parameters.put("brandId", campaign.brandId());
        parameters.put("name", campaign.name());
        parameters.put("channel", campaign.channel());
        parameters.put("consentPurpose", campaign.consentPurpose());
        parameters.put("audienceId", campaign.audienceId());
        parameters.put("templateKey", campaign.templateKey());
        parameters.put("recipientCap", campaign.recipientCap());
        parameters.put("ceiling", campaign.costCeilingMinor());
        parameters.put("currency", campaign.currency());
        parameters.put("timezone", campaign.timezone());
        parameters.put("benefitOfferId", campaign.benefitOfferId());
        parameters.put("accrualRuleId", campaign.loyaltyAccrualRuleId());
        parameters.put("createdBy", campaign.createdBy());
        parameters.put("now", utc(campaign.createdAt()));

        jdbc.sql("""
                INSERT INTO marketing.campaigns (
                    id, tenant_id, brand_id, name, channel, consent_purpose, status,
                    audience_id, template_key, recipient_cap, cost_ceiling_minor, currency,
                    timezone, benefit_offer_id, loyalty_accrual_rule_id, created_by,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, :channel, :consentPurpose, 'DRAFT',
                    :audienceId, :templateKey, :recipientCap, :ceiling, :currency,
                    :timezone, :benefitOfferId, :accrualRuleId, :createdBy,
                    :now, :now)
                """).params(parameters).update();
    }

    public Optional<CampaignRow> find(UUID tenantId, UUID campaignId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, name, channel, consent_purpose, status,
                       audience_id, audience_snapshot_id, template_key, timezone,
                       recipient_cap, estimated_recipients, estimated_cost_low_minor,
                       estimated_cost_high_minor, cost_ceiling_minor, reserved_cost_minor,
                       spent_cost_minor, reserved_recipients, currency, benefit_offer_id,
                       loyalty_accrual_rule_id, created_by, approved_by, version
                  FROM marketing.campaigns
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", campaignId)
                .query(JdbcCampaignStore::campaignRow)
                .optional();
    }

    /**
     * Moves the campaign, refusing the move if somebody else moved it first.
     *
     * <p>The {@code from} status is in the predicate rather than checked in Java.
     * Two operators pressing halt and pause on the same second is not exotic, and a
     * read-then-write here would let the later one silently overwrite the earlier.
     */
    public boolean transition(UUID tenantId, UUID campaignId, CampaignStatus from, CampaignStatus to, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.campaigns
                   SET status = :to, version = version + 1, updated_at = :now,
                       started_at = CASE WHEN :to = 'SENDING' AND started_at IS NULL
                                         THEN :now ELSE started_at END,
                       completed_at = CASE WHEN :to IN ('SENT', 'PARTIALLY_SENT')
                                           THEN :now ELSE completed_at END
                 WHERE tenant_id = :tenantId AND id = :id AND status = :from
                """)
                        .param("tenantId", tenantId)
                        .param("id", campaignId)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** A halt carries the reason in the same statement that stops the campaign. */
    public boolean halt(
            UUID tenantId, UUID campaignId, CampaignStatus from, CampaignStatus to, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.campaigns
                   SET status = :to, halted_reason = :reason, completed_at = :now,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = :from
                """)
                        .param("tenantId", tenantId)
                        .param("id", campaignId)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("reason", reason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public void recordEstimate(
            UUID tenantId,
            UUID campaignId,
            UUID snapshotId,
            int recipients,
            @Nullable Long costLowMinor,
            @Nullable Long costHighMinor,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", campaignId);
        parameters.put("snapshotId", snapshotId);
        parameters.put("recipients", recipients);
        parameters.put("low", costLowMinor);
        parameters.put("high", costHighMinor);
        parameters.put("now", utc(now));

        jdbc.sql("""
                UPDATE marketing.campaigns
                   SET audience_snapshot_id = :snapshotId,
                       estimated_recipients = :recipients,
                       estimated_cost_low_minor = :low,
                       estimated_cost_high_minor = :high,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """).params(parameters).update();
    }

    /**
     * Records the second signature.
     *
     * <p>The four-eyes rule is in the predicate as well as in the CHECK. Enforcing
     * it only in Java would leave the window between reading the author and writing
     * the approver, and enforcing it only in the CHECK would produce a constraint
     * violation where the caller wants a refusal it can explain.
     */
    public boolean approve(UUID tenantId, UUID campaignId, UUID approvedBy, UUID approvalId, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.campaigns
                   SET status = 'APPROVED', approved_by = :approvedBy, approval_id = :approvalId,
                       approved_at = :now, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = 'IN_REVIEW'
                   AND created_by <> :approvedBy
                """)
                        .param("tenantId", tenantId)
                        .param("id", campaignId)
                        .param("approvedBy", approvedBy)
                        .param("approvalId", approvalId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Reserves capacity for one batch, or refuses.
     *
     * <p>The insert goes first and its conflict is the idempotency check: a
     * replayed batch sequence finds its row already present, updates nothing, and
     * reserves nothing. Only a genuinely new batch reaches the conditional update,
     * and only if the campaign is still sending and the reservation still fits
     * under both limits.
     *
     * @return the outcome, so the caller can tell "no budget" from "already done"
     *         from "the campaign was halted while I was working"
     */
    public BatchClaim claimBatch(
            UUID tenantId,
            UUID campaignId,
            UUID snapshotId,
            int batchSequence,
            int recipients,
            long costMinor,
            Instant now) {

        Map<String, Object> insert = new HashMap<>();
        insert.put("campaignId", campaignId);
        insert.put("tenantId", tenantId);
        insert.put("snapshotId", snapshotId);
        insert.put("sequence", batchSequence);
        insert.put("recipients", recipients);
        insert.put("cost", costMinor);
        insert.put("now", utc(now));

        boolean fresh = jdbc.sql("""
                INSERT INTO marketing.campaign_batches (
                    campaign_id, tenant_id, snapshot_id, batch_sequence,
                    claimed_recipients, reserved_cost_minor, claimed_at)
                VALUES (:campaignId, :tenantId, :snapshotId, :sequence,
                    :recipients, :cost, :now)
                ON CONFLICT (campaign_id, snapshot_id, batch_sequence) DO NOTHING
                """).params(insert).update() == 1;

        if (!fresh) {
            return BatchClaim.ALREADY_CLAIMED;
        }

        Map<String, Object> reserve = new HashMap<>();
        reserve.put("tenantId", tenantId);
        reserve.put("id", campaignId);
        reserve.put("recipients", recipients);
        reserve.put("cost", costMinor);
        reserve.put("now", utc(now));

        boolean reserved = jdbc.sql("""
                UPDATE marketing.campaigns
                   SET reserved_recipients = reserved_recipients + :recipients,
                       reserved_cost_minor = reserved_cost_minor + :cost,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND id = :id
                   AND status = 'SENDING'
                   AND reserved_recipients + :recipients <= recipient_cap
                   AND (cost_ceiling_minor IS NULL
                        OR reserved_cost_minor + :cost <= cost_ceiling_minor)
                """).params(reserve).update() == 1;

        return reserved ? BatchClaim.RESERVED : BatchClaim.REFUSED;
    }

    /** What a batch claim did. */
    public enum BatchClaim {
        RESERVED,
        /** The sequence was already claimed; the replay must produce nothing. */
        ALREADY_CLAIMED,
        /** The ceiling, the cap, or a halt refused it. */
        REFUSED
    }

    /** Turns a reservation into spend once the messages for a batch exist. */
    public void recordSpend(UUID tenantId, UUID campaignId, long costMinor, Instant now) {
        jdbc.sql("""
                UPDATE marketing.campaigns
                   SET spent_cost_minor = spent_cost_minor + :cost,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", campaignId)
                .param("cost", costMinor)
                .param("now", utc(now))
                .update();
    }

    // ------------------------------------------------------------ recipients

    /**
     * The per-recipient receipt, whichever way it went.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on the pair, which is ADR 0044's stated
     * idempotency key. A replayed expansion cannot overwrite a recipient row: the
     * first decision about a customer is the one that was acted on, and rewriting
     * it would erase the reason a message was refused.
     *
     * @return true when this call wrote the row
     */
    public boolean recordRecipient(
            UUID tenantId,
            UUID campaignId,
            UUID accountId,
            int sequence,
            String status,
            @Nullable UUID notificationId,
            @Nullable RefusalReason refusal,
            @Nullable String refusalDetail,
            @Nullable Instant deferredUntil,
            Instant now) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("campaignId", campaignId);
        parameters.put("tenantId", tenantId);
        parameters.put("accountId", accountId);
        parameters.put("sequence", sequence);
        parameters.put("status", status);
        parameters.put("notificationId", notificationId);
        parameters.put("refusal", refusal == null ? null : refusal.name());
        parameters.put("refusalDetail", refusalDetail);
        parameters.put("deferredUntil", deferredUntil == null ? null : utc(deferredUntil));
        parameters.put("now", utc(now));

        return jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status,
                    notification_id, refusal_reason, refusal_detail, deferred_until,
                    created_at, updated_at)
                VALUES (:campaignId, :tenantId, :accountId, :sequence, :status,
                    :notificationId, :refusal, :refusalDetail, :deferredUntil,
                    :now, :now)
                ON CONFLICT (campaign_id, customer_account_id) DO NOTHING
                """).params(parameters).update() == 1;
    }

    /**
     * The highest account id already recorded against this campaign.
     *
     * <p>The expansion cursor. Keyset rather than an offset, and stored implicitly
     * in the recipient rows rather than on the campaign, so a worker that dies
     * between claiming a batch and writing its rows resumes from what actually
     * exists rather than from a counter that ran ahead of it.
     */
    public Optional<UUID> lastRecipientAccountId(UUID tenantId, UUID campaignId) {
        // ORDER BY ... LIMIT 1 rather than MAX(): PostgreSQL has no aggregate over
        // uuid, and the ordered read is what the primary key serves anyway.
        return jdbc.sql("""
                SELECT customer_account_id
                  FROM marketing.campaign_recipients
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                 ORDER BY customer_account_id DESC
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query(UUID.class)
                .optional();
    }

    public int recipientCount(UUID tenantId, UUID campaignId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM marketing.campaign_recipients
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query(Integer.class)
                .single();
    }

    public int nextBatchSequence(UUID tenantId, UUID campaignId) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(batch_sequence) + 1, 0)
                  FROM marketing.campaign_batches
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query(Integer.class)
                .single();
    }

    public List<RecipientRow> recipients(UUID tenantId, UUID campaignId, int limit) {
        return jdbc.sql("""
                SELECT customer_account_id, sequence, status, notification_id,
                       refusal_reason, refusal_detail, deferred_until, terminal_status
                  FROM marketing.campaign_recipients
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                 ORDER BY sequence
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .param("limit", limit)
                .query((ResultSet row, int number) -> new RecipientRow(
                        row.getObject("customer_account_id", UUID.class),
                        row.getInt("sequence"),
                        row.getString("status"),
                        row.getObject("notification_id", UUID.class),
                        row.getString("refusal_reason"),
                        row.getString("refusal_detail"),
                        instant(row.getObject("deferred_until", OffsetDateTime.class)),
                        row.getString("terminal_status")))
                .list();
    }

    /** How many recipients ended each way. What a campaign report reads. */
    public Map<String, Integer> recipientCounts(UUID tenantId, UUID campaignId) {
        return jdbc
                .sql("""
                SELECT status, COUNT(*) AS total
                  FROM marketing.campaign_recipients
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                 GROUP BY status
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query((ResultSet row, int number) -> Map.entry(row.getString("status"), row.getInt("total")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static CampaignRow campaignRow(ResultSet row, int number) throws java.sql.SQLException {
        return new CampaignRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("name"),
                row.getString("channel"),
                row.getString("consent_purpose"),
                CampaignStatus.valueOf(row.getString("status")),
                row.getObject("audience_id", UUID.class),
                row.getObject("audience_snapshot_id", UUID.class),
                row.getString("template_key"),
                row.getString("timezone"),
                row.getInt("recipient_cap"),
                row.getObject("estimated_recipients", Integer.class),
                row.getObject("estimated_cost_low_minor", Long.class),
                row.getObject("estimated_cost_high_minor", Long.class),
                row.getObject("cost_ceiling_minor", Long.class),
                row.getLong("reserved_cost_minor"),
                row.getLong("spent_cost_minor"),
                row.getInt("reserved_recipients"),
                row.getString("currency"),
                row.getObject("benefit_offer_id", UUID.class),
                row.getObject("loyalty_accrual_rule_id", UUID.class),
                row.getObject("created_by", UUID.class),
                row.getObject("approved_by", UUID.class),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record NewCampaign(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String name,
            String channel,
            String consentPurpose,
            UUID audienceId,
            String templateKey,
            int recipientCap,
            Long costCeilingMinor,
            String currency,
            String timezone,
            @Nullable UUID benefitOfferId,
            @Nullable UUID loyaltyAccrualRuleId,
            UUID createdBy,
            Instant createdAt) {}

    public record CampaignRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String name,
            String channel,
            String consentPurpose,
            CampaignStatus status,
            UUID audienceId,
            UUID snapshotId,
            String templateKey,
            String timezone,
            int recipientCap,
            Integer estimatedRecipients,
            Long estimatedCostLowMinor,
            Long estimatedCostHighMinor,
            Long costCeilingMinor,
            long reservedCostMinor,
            long spentCostMinor,
            int reservedRecipients,
            String currency,
            UUID benefitOfferId,
            UUID loyaltyAccrualRuleId,
            UUID createdBy,
            UUID approvedBy,
            int version) {}

    public record RecipientRow(
            UUID customerAccountId,
            int sequence,
            String status,
            UUID notificationId,
            String refusalReason,
            String refusalDetail,
            @Nullable Instant deferredUntil,
            String terminalStatus) {}
}
