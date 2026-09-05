package uz.horecaos.platform.referral.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Referral programs, codes, and redemptions (a new ADR, riding on ADR 0046's
 * loyalty ledger for the reward itself).
 *
 * <p>Three tables and three different write disciplines, each matching what the
 * table is for. {@code referral.programs} is authored draft-then-activate, the
 * identical shape {@code JdbcLoyaltyStore} already uses for accrual rules and
 * the redemption policy: retire-then-promote in one statement pair, one
 * {@code ACTIVE} row per brand. {@code referral.codes} is minted once per
 * customer and otherwise only read. {@code referral.redemptions} moves through
 * a small state machine by conditional {@code UPDATE}, the same
 * {@code WHERE status = 'PENDING'} discipline that makes a replayed qualifying
 * event a no-op rather than a second reward.
 */
@Repository
public class JdbcReferralStore {

    private final JdbcClient jdbc;

    public JdbcReferralStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ programs

    /** A program as its resolver needs it — what a redemption snapshots at creation. */
    public record ProgramRow(
            UUID id,
            int version,
            String rewardShape,
            long referrerRewardMinor,
            long refereeRewardMinor,
            String rewardCurrency,
            @Nullable Integer maxRewardedReferralsPerReferrer,
            int redemptionWindowDays,
            int rewardLotLifetimeDays) {}

    /** The brand's live program, or empty when it runs none — the same silence ADR 0046 chose for accrual. */
    public Optional<ProgramRow> activeProgram(UUID tenantId, UUID brandId, Instant asOf) {
        return jdbc.sql("""
                SELECT id, version, reward_shape, referrer_reward_minor, referee_reward_minor,
                       reward_currency, max_rewarded_referrals_per_referrer,
                       redemption_window_days, reward_lot_lifetime_days
                  FROM referral.programs
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND status = 'ACTIVE'
                   AND valid_from <= :asOf
                   AND (valid_until IS NULL OR valid_until > :asOf)
                 ORDER BY valid_from DESC
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("asOf", utc(asOf))
                .query(JdbcReferralStore::toProgramRow)
                .optional();
    }

    /** One program as the authoring screen needs it — every lifecycle field. */
    public record ProgramAuthoringRow(
            UUID id,
            String rewardShape,
            long referrerRewardMinor,
            long refereeRewardMinor,
            String rewardCurrency,
            @Nullable Integer maxRewardedReferralsPerReferrer,
            int redemptionWindowDays,
            int rewardLotLifetimeDays,
            String status,
            int version,
            Instant validFrom,
            @Nullable Instant validUntil) {}

    public List<ProgramAuthoringRow> listPrograms(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id, reward_shape, referrer_reward_minor, referee_reward_minor, reward_currency,
                       max_rewarded_referrals_per_referrer, redemption_window_days, reward_lot_lifetime_days,
                       status, version, valid_from, valid_until
                  FROM referral.programs
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                 ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                          valid_from DESC
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcReferralStore::toProgramAuthoringRow)
                .list();
    }

    public Optional<ProgramAuthoringRow> findProgramById(UUID tenantId, UUID brandId, UUID programId) {
        return jdbc.sql("""
                SELECT id, reward_shape, referrer_reward_minor, referee_reward_minor, reward_currency,
                       max_rewarded_referrals_per_referrer, redemption_window_days, reward_lot_lifetime_days,
                       status, version, valid_from, valid_until
                  FROM referral.programs
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :programId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("programId", programId)
                .query(JdbcReferralStore::toProgramAuthoringRow)
                .optional();
    }

    /** Drafts a program. Never DEFAULT ACTIVE — a separate {@link #activateProgram} call promotes it. */
    public void insertProgramDraft(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String rewardShape,
            long referrerRewardMinor,
            long refereeRewardMinor,
            String rewardCurrency,
            @Nullable Integer maxRewardedReferralsPerReferrer,
            int redemptionWindowDays,
            int rewardLotLifetimeDays,
            Instant validFrom,
            @Nullable Instant validUntil,
            Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("shape", rewardShape);
        params.put("referrerReward", referrerRewardMinor);
        params.put("refereeReward", refereeRewardMinor);
        params.put("currency", rewardCurrency);
        params.put("cap", maxRewardedReferralsPerReferrer);
        params.put("window", redemptionWindowDays);
        params.put("lotLifetime", rewardLotLifetimeDays);
        params.put("validFrom", utc(validFrom));
        params.put("validUntil", utc(validUntil));
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO referral.programs (
                    id, tenant_id, brand_id, reward_shape, referrer_reward_minor, referee_reward_minor,
                    reward_currency, max_rewarded_referrals_per_referrer, redemption_window_days,
                    reward_lot_lifetime_days, status, version, valid_from, valid_until, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :shape, :referrerReward, :refereeReward,
                    :currency, :cap, :window, :lotLifetime, 'DRAFT', 1, :validFrom, :validUntil, :now, :now)
                """).params(params).update();
    }

    /**
     * Retires whichever program currently holds this brand, then promotes the
     * draft — the same retire-then-promote shape
     * {@code JdbcLoyaltyStore.activateRedemptionPolicy} uses, for the identical
     * reason: a brand's live set must never hold two.
     *
     * @return 1 if this call promoted the draft, 0 if it was raced or was not a DRAFT
     */
    public int activateProgram(UUID tenantId, UUID brandId, UUID programId, Instant now) {
        jdbc.sql("""
                UPDATE referral.programs
                   SET status = 'RETIRED', updated_at = :now
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND status = 'ACTIVE' AND id <> :programId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("programId", programId)
                .param("now", utc(now))
                .update();

        return jdbc.sql("""
                UPDATE referral.programs
                   SET status = 'ACTIVE', updated_at = :now
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :programId
                   AND status = 'DRAFT'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("programId", programId)
                .param("now", utc(now))
                .update();
    }

    public int retireProgram(UUID tenantId, UUID brandId, UUID programId, Instant now) {
        return jdbc.sql("""
                UPDATE referral.programs
                   SET status = 'RETIRED', updated_at = :now
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :programId
                   AND status IN ('DRAFT', 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("programId", programId)
                .param("now", utc(now))
                .update();
    }

    // ---------------------------------------------------------------- codes

    public record CodeRow(UUID id, UUID tenantId, UUID brandId, UUID customerAccountId, String code, String status) {}

    public Optional<CodeRow> findCodeByOwner(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, customer_account_id, code, status
                  FROM referral.codes
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND customer_account_id = :customerAccountId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerAccountId", customerAccountId)
                .query(JdbcReferralStore::toCodeRow)
                .optional();
    }

    public Optional<CodeRow> findCodeByValue(UUID tenantId, String code) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, customer_account_id, code, status
                  FROM referral.codes
                 WHERE tenant_id = :tenantId AND code = :code
                """)
                .param("tenantId", tenantId)
                .param("code", code)
                .query(JdbcReferralStore::toCodeRow)
                .optional();
    }

    /**
     * Attempts to mint a code for this owner, first-writer-wins on the owner's
     * own uniqueness.
     *
     * <p>{@code ON CONFLICT} names {@code uq_referral_code_owner} only, so a
     * collision on {@code uq_referral_code_value} — a different owner already
     * holding the generated code — is not swallowed here: it throws, and
     * {@code ReferralCodeService} retries with a freshly generated code, the
     * same "unique by index with retry on collision" ADR 0044 already
     * specifies for a coded benefit grant.
     *
     * @return the row that now exists for this owner, whether this call
     *         inserted it or an earlier concurrent call did
     * @throws DataIntegrityViolationException when {@code code} collides with
     *                                          a different owner's code
     */
    public CodeRow insertCodeIfAbsent(
            UUID id, UUID tenantId, UUID brandId, UUID customerAccountId, String code, Instant now) {
        jdbc.sql("""
                INSERT INTO referral.codes (
                    id, tenant_id, brand_id, customer_account_id, code, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :customerAccountId, :code, 'ACTIVE', 1, :now, :now)
                ON CONFLICT ON CONSTRAINT uq_referral_code_owner DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerAccountId", customerAccountId)
                .param("code", code)
                .param("now", utc(now))
                .update();

        return findCodeByOwner(tenantId, brandId, customerAccountId)
                .orElseThrow(() -> new IllegalStateException("A referral code was neither inserted nor found"));
    }

    // --------------------------------------------------------- redemptions

    public record RedemptionRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID codeId,
            UUID programId,
            int programVersion,
            UUID referrerCustomerAccountId,
            UUID refereeCustomerAccountId,
            String status,
            Instant redeemedAt,
            Instant expiresAt,
            @Nullable UUID qualifyingOrderId,
            @Nullable Instant rewardedAt,
            long referrerRewardMinor,
            long refereeRewardMinor,
            @Nullable UUID referrerEntryId,
            @Nullable UUID refereeEntryId,
            @Nullable String referrerSkipReason,
            int version) {}

    public Optional<RedemptionRow> findRedemptionByReferee(UUID tenantId, UUID brandId, UUID refereeCustomerAccountId) {
        return jdbc.sql("""
                SELECT * FROM referral.redemptions
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND referee_customer_account_id = :refereeId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("refereeId", refereeCustomerAccountId)
                .query(JdbcReferralStore::toRedemptionRow)
                .optional();
    }

    /**
     * The same read, locking — so a concurrent delivery of the same
     * order-completed fact queues behind whichever transaction reaches this
     * row first, rather than both reading {@code PENDING} and both attempting
     * to pay. The same discipline {@code JdbcLoyaltyStore.availableLots}
     * already uses for a concurrent redemption planning against one account's
     * lots.
     *
     * <p>{@code ReferralQualificationService} is the only caller: whichever
     * transaction gets here second sees this row already {@code REWARDED},
     * {@code EXPIRED}, or {@code VOIDED} once the first commits, and does
     * nothing further.
     */
    public Optional<RedemptionRow> findRedemptionByRefereeForUpdate(
            UUID tenantId, UUID brandId, UUID refereeCustomerAccountId) {
        return jdbc.sql("""
                SELECT * FROM referral.redemptions
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND referee_customer_account_id = :refereeId
                 FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("refereeId", refereeCustomerAccountId)
                .query(JdbcReferralStore::toRedemptionRow)
                .optional();
    }

    /** A program row by identity alone, ignoring status and validity — a historical snapshot lookup. */
    public Optional<ProgramRow> findProgramRowById(UUID tenantId, UUID programId) {
        return jdbc.sql("""
                SELECT id, version, reward_shape, referrer_reward_minor, referee_reward_minor,
                       reward_currency, max_rewarded_referrals_per_referrer,
                       redemption_window_days, reward_lot_lifetime_days
                  FROM referral.programs
                 WHERE tenant_id = :tenantId AND id = :programId
                """)
                .param("tenantId", tenantId)
                .param("programId", programId)
                .query(JdbcReferralStore::toProgramRow)
                .optional();
    }

    public Optional<RedemptionRow> findRedemptionById(UUID tenantId, UUID redemptionId) {
        return jdbc.sql("SELECT * FROM referral.redemptions WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", redemptionId)
                .query(JdbcReferralStore::toRedemptionRow)
                .optional();
    }

    /**
     * Records a new customer's use of a code.
     *
     * <p>The unique index on {@code (tenant_id, brand_id, referee_customer_account_id)}
     * is what actually stops stacking; this insert either succeeds once or the
     * caller catches the violation and reports the account's one existing
     * redemption instead. There is no {@code ON CONFLICT} here — unlike a code
     * mint, a second redemption attempt is refused, not silently answered with
     * the first one, because {@code ReferralRedemptionService} needs to
     * distinguish "you already redeemed" from "here is what you redeemed".
     */
    public void insertRedemption(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID codeId,
            UUID programId,
            int programVersion,
            UUID referrerCustomerAccountId,
            UUID refereeCustomerAccountId,
            Instant redeemedAt,
            Instant expiresAt,
            long referrerRewardMinor,
            long refereeRewardMinor,
            String idempotencyKey,
            Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("codeId", codeId);
        params.put("programId", programId);
        params.put("programVersion", programVersion);
        params.put("referrerId", referrerCustomerAccountId);
        params.put("refereeId", refereeCustomerAccountId);
        params.put("redeemedAt", utc(redeemedAt));
        params.put("expiresAt", utc(expiresAt));
        params.put("referrerReward", referrerRewardMinor);
        params.put("refereeReward", refereeRewardMinor);
        params.put("idempotencyKey", idempotencyKey);
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO referral.redemptions (
                    id, tenant_id, brand_id, code_id, program_id, program_version,
                    referrer_customer_account_id, referee_customer_account_id, status,
                    redeemed_at, expires_at, referrer_reward_minor, referee_reward_minor,
                    idempotency_key, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :codeId, :programId, :programVersion,
                    :referrerId, :refereeId, 'PENDING',
                    :redeemedAt, :expiresAt, :referrerReward, :refereeReward,
                    :idempotencyKey, 1, :now, :now)
                """).params(params).update();
    }

    /**
     * The one transition that pays a redemption out.
     *
     * <p>{@code WHERE status = 'PENDING' AND expires_at > :now} is the whole of
     * the idempotency and the expiry-at-the-boundary decision: a replay of the
     * same qualifying event, or a qualifying event that lands after the window
     * closed, both match zero rows and change nothing.
     *
     * @return true when this call was the one that recorded the reward
     */
    public boolean markRewarded(
            UUID tenantId,
            UUID redemptionId,
            UUID qualifyingOrderId,
            Instant rewardedAt,
            @Nullable UUID referrerEntryId,
            @Nullable UUID refereeEntryId,
            @Nullable String referrerSkipReason,
            Instant now) {
        return jdbc.sql("""
                UPDATE referral.redemptions
                   SET status = 'REWARDED',
                       qualifying_order_id = :orderId,
                       rewarded_at = :rewardedAt,
                       referrer_entry_id = :referrerEntryId,
                       referee_entry_id = :refereeEntryId,
                       referrer_skip_reason = :skipReason,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = 'PENDING' AND expires_at > :now
                """)
                        .param("tenantId", tenantId)
                        .param("id", redemptionId)
                        .param("orderId", qualifyingOrderId)
                        .param("rewardedAt", utc(rewardedAt))
                        .param("referrerEntryId", referrerEntryId)
                        .param("refereeEntryId", refereeEntryId)
                        .param("skipReason", referrerSkipReason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Flips a redemption whose window has closed to {@code EXPIRED}, judged at
     * the moment something asks rather than by a scheduled sweep.
     *
     * @return true when this call was the one that expired it
     */
    public boolean markExpired(UUID tenantId, UUID redemptionId, Instant now) {
        return jdbc.sql("""
                UPDATE referral.redemptions
                   SET status = 'EXPIRED', version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = 'PENDING' AND expires_at <= :now
                """)
                        .param("tenantId", tenantId)
                        .param("id", redemptionId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** How many times this referrer has already been rewarded by this program — what the cap is checked against. */
    public long countRewardedForReferrer(UUID tenantId, UUID brandId, UUID programId, UUID referrerCustomerAccountId) {
        Long count = jdbc.sql("""
                SELECT COUNT(*) FROM referral.redemptions
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND program_id = :programId
                   AND referrer_customer_account_id = :referrerId
                   AND status = 'REWARDED' AND referrer_entry_id IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("programId", programId)
                .param("referrerId", referrerCustomerAccountId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    /** Every redemption this brand has, newest first — the operations read side (§6.6). */
    public List<RedemptionRow> listRedemptions(UUID tenantId, UUID brandId, int limit) {
        return jdbc.sql("""
                SELECT * FROM referral.redemptions
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                 ORDER BY redeemed_at DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("limit", limit)
                .query(JdbcReferralStore::toRedemptionRow)
                .list();
    }

    /** Summary counters for the operations read side: codes issued, redemptions by status, points paid out. */
    public record BrandSummary(
            long codesIssued,
            long pendingRedemptions,
            long rewardedRedemptions,
            long expiredOrVoidedRedemptions,
            long pointsPaidOutMinor) {}

    public BrandSummary summary(UUID tenantId, UUID brandId) {
        long codesIssued = jdbc.sql(
                        "SELECT COUNT(*) FROM referral.codes WHERE tenant_id = :tenantId AND brand_id = :brandId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Long.class)
                .single();
        return jdbc.sql("""
                SELECT
                    COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                    COUNT(*) FILTER (WHERE status = 'REWARDED') AS rewarded,
                    COUNT(*) FILTER (WHERE status IN ('EXPIRED', 'VOIDED')) AS closed,
                    COALESCE(SUM(CASE WHEN referrer_entry_id IS NOT NULL THEN referrer_reward_minor ELSE 0 END), 0)
                        + COALESCE(SUM(CASE WHEN referee_entry_id IS NOT NULL THEN referee_reward_minor ELSE 0 END), 0)
                        AS paid_out
                  FROM referral.redemptions
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new BrandSummary(
                        codesIssued,
                        row.getLong("pending"),
                        row.getLong("rewarded"),
                        row.getLong("closed"),
                        row.getLong("paid_out")))
                .single();
    }

    // ------------------------------------------------------------- mapping

    private static ProgramRow toProgramRow(ResultSet row, int number) throws SQLException {
        return new ProgramRow(
                row.getObject("id", UUID.class),
                row.getInt("version"),
                row.getString("reward_shape"),
                row.getLong("referrer_reward_minor"),
                row.getLong("referee_reward_minor"),
                row.getString("reward_currency"),
                row.getObject("max_rewarded_referrals_per_referrer", Integer.class),
                row.getInt("redemption_window_days"),
                row.getInt("reward_lot_lifetime_days"));
    }

    private static ProgramAuthoringRow toProgramAuthoringRow(ResultSet row, int number) throws SQLException {
        return new ProgramAuthoringRow(
                row.getObject("id", UUID.class),
                row.getString("reward_shape"),
                row.getLong("referrer_reward_minor"),
                row.getLong("referee_reward_minor"),
                row.getString("reward_currency"),
                row.getObject("max_rewarded_referrals_per_referrer", Integer.class),
                row.getInt("redemption_window_days"),
                row.getInt("reward_lot_lifetime_days"),
                row.getString("status"),
                row.getInt("version"),
                requiredInstant(row, "valid_from"),
                instant(row, "valid_until"));
    }

    private static CodeRow toCodeRow(ResultSet row, int number) throws SQLException {
        return new CodeRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("code"),
                row.getString("status"));
    }

    private static RedemptionRow toRedemptionRow(ResultSet row, int number) throws SQLException {
        return new RedemptionRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("code_id", UUID.class),
                row.getObject("program_id", UUID.class),
                row.getInt("program_version"),
                row.getObject("referrer_customer_account_id", UUID.class),
                row.getObject("referee_customer_account_id", UUID.class),
                row.getString("status"),
                requiredInstant(row, "redeemed_at"),
                requiredInstant(row, "expires_at"),
                row.getObject("qualifying_order_id", UUID.class),
                instant(row, "rewarded_at"),
                row.getLong("referrer_reward_minor"),
                row.getLong("referee_reward_minor"),
                row.getObject("referrer_entry_id", UUID.class),
                row.getObject("referee_entry_id", UUID.class),
                row.getString("referrer_skip_reason"),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant requiredInstant(ResultSet row, String column) throws SQLException {
        return Objects.requireNonNull(instant(row, column), () -> column + " was unexpectedly null");
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
