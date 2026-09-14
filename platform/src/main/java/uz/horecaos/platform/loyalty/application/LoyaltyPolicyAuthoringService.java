package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccrualRuleAuthoringRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.RedemptionPolicyAuthoringRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring a brand's own accrual rate and redemption cap (ADR 0046,
 * operations §6.3 Loyalty), gated by {@code LOYALTY_POLICY_MANAGE} rather than
 * {@code LOYALTY_ADJUST} for the reason that capability's own Javadoc gives:
 * these are the numbers, not one customer's balance.
 *
 * <p><strong>There is no code default for either table, and this class does not
 * add one.</strong> {@link LoyaltyPolicyService} already says a brand with no
 * active row neither accrues nor redeems; the six numbers ADR 0046 proposes are
 * marked there as pending product and finance confirmation, not as constants to
 * compile in. So every draft here starts from whatever the operator types —
 * this class validates the shape of a rule, never its economics.
 *
 * <p><strong>Draft, then activate, never both in one call.</strong> The same
 * split {@code DeliveryTariffService} uses for a rate table, for the same
 * reason stated sharper here: raising an accrual rate is a tax decision (a
 * redemption is a per-line discount in {@code fiscal} — ADR 0046's "What
 * reaches the receipt"), and the person who types a misplaced digit into a
 * percentage field must not be the only one who ever reads it back.
 *
 * <p>Activation retires whichever rule or policy currently holds the same live
 * scope before promoting the draft, in one transaction — so a brand's
 * resolvable set never holds two {@code ACTIVE} rows for one scope, the
 * property {@link LoyaltyPolicyService#accrualRule} and
 * {@link LoyaltyPolicyService#redemptionPolicy} are read against.
 */
@Service
public class LoyaltyPolicyAuthoringService {

    private final JdbcLoyaltyStore store;
    private final Clock clock;

    public LoyaltyPolicyAuthoringService(JdbcLoyaltyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    // ------------------------------------------------------------- accrual

    /**
     * @param scopeType   {@code BRAND}, {@code LOCATION}, or {@code CHANNEL} — the
     *                    same narrowest-first ladder {@link LoyaltyPolicyService}
     *                    resolves against
     * @param scopeId     required exactly when {@code scopeType} is not
     *                    {@code BRAND}
     * @param validFrom   when this rule starts applying, or null for "now"
     */
    public record AccrualRuleDraft(
            String scopeType,
            @Nullable UUID scopeId,
            int rateBasisPoints,
            @Nullable Long maxAccrualMinor,
            int earnDelayHours,
            int lotLifetimeDays,
            int expiryWarningDays,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    @Transactional(readOnly = true)
    public List<AccrualRuleAuthoringRow> listAccrualRules(UUID tenantId, UUID brandId) {
        return store.listAccrualRules(tenantId, brandId);
    }

    @Transactional
    public AccrualRuleAuthoringRow draftAccrualRule(UUID tenantId, UUID brandId, AccrualRuleDraft draft) {
        validateAccrualDraft(draft);
        validateAccrualScope(tenantId, brandId, draft);
        Instant now = clock.instant();
        Instant validFrom = draft.validFrom() != null ? draft.validFrom() : now;
        UUID id = UUID.randomUUID();
        store.insertAccrualRuleDraft(
                id,
                tenantId,
                brandId,
                draft.scopeType(),
                draft.scopeId(),
                draft.rateBasisPoints(),
                draft.maxAccrualMinor(),
                draft.earnDelayHours(),
                draft.lotLifetimeDays(),
                draft.expiryWarningDays(),
                validFrom,
                draft.validUntil(),
                now);
        // Built from the inputs just validated and inserted, rather than a
        // second SELECT: every field on this row is one this method itself
        // just decided, so there is nothing a re-read could disagree about.
        return new AccrualRuleAuthoringRow(
                id,
                draft.scopeType(),
                draft.scopeId(),
                draft.rateBasisPoints(),
                draft.maxAccrualMinor(),
                draft.earnDelayHours(),
                draft.lotLifetimeDays(),
                draft.expiryWarningDays(),
                "DRAFT",
                1,
                validFrom,
                draft.validUntil());
    }

    /** Retires whichever rule currently lives at this scope, then promotes the draft. */
    @Transactional
    public void activateAccrualRule(UUID tenantId, UUID brandId, UUID ruleId) {
        AccrualRuleAuthoringRow rule = store.findAccrualRuleById(tenantId, brandId, ruleId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such accrual rule"));
        if (!"DRAFT".equals(rule.status())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Only a DRAFT rule can be activated; this one is " + rule.status());
        }
        Instant now = clock.instant();
        if (store.activateAccrualRule(tenantId, brandId, ruleId, rule.scopeType(), rule.scopeId(), now) != 1) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This rule was activated or retired by someone else");
        }
    }

    /** Withdraws a live rule, or discards a draft nobody activated. */
    @Transactional
    public void retireAccrualRule(UUID tenantId, UUID brandId, UUID ruleId) {
        if (store.retireAccrualRule(tenantId, brandId, ruleId, clock.instant()) != 1) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such rule to retire");
        }
    }

    private void validateAccrualDraft(AccrualRuleDraft draft) {
        List<String> problems = new ArrayList<>();
        if (!List.of("BRAND", "LOCATION", "CHANNEL").contains(draft.scopeType())) {
            problems.add("scopeType must be BRAND, LOCATION, or CHANNEL");
        }
        if ("BRAND".equals(draft.scopeType()) != (draft.scopeId() == null)) {
            problems.add("A BRAND rule carries no scopeId; a LOCATION or CHANNEL rule names one");
        }
        if (draft.rateBasisPoints() < 0 || draft.rateBasisPoints() > 10_000) {
            problems.add("rateBasisPoints must be between 0 and 10000");
        }
        if (draft.maxAccrualMinor() != null && draft.maxAccrualMinor() <= 0) {
            problems.add("maxAccrualMinor must be positive when set, or omitted for uncapped");
        }
        if (draft.earnDelayHours() < 0) {
            problems.add("earnDelayHours cannot be negative");
        }
        if (draft.lotLifetimeDays() <= 0) {
            problems.add("lotLifetimeDays must be positive");
        }
        if (draft.expiryWarningDays() < 0
                || (draft.lotLifetimeDays() > 0 && draft.expiryWarningDays() >= draft.lotLifetimeDays())) {
            problems.add("expiryWarningDays must be at least 0 and less than lotLifetimeDays");
        }
        if (draft.validUntil() != null
                && draft.validFrom() != null
                && !draft.validUntil().isAfter(draft.validFrom())) {
            problems.add("validUntil must be after validFrom");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
    }

    /**
     * The check {@link #validateAccrualDraft} could not run: whether {@code
     * scopeId} actually names a row, not merely a well-formed UUID.
     *
     * <p>Run separately, and after the shape check, because it is a database
     * round trip and the shape check already refuses a BRAND rule carrying
     * one or a LOCATION/CHANNEL rule carrying none — this only runs once a
     * scopeId is known to be present and expected. Without it a mistyped or
     * stale location id persisted silently and {@code
     * JdbcLoyaltyStore.accrualRule}'s own narrowest-first resolver simply
     * never matched it, so every order at that location fell back to the
     * brand's rule with no error anywhere — the failure V0308's trigger also
     * guards, this being the one an operator actually sees as a clean 422
     * instead of a raw {@code foreign_key_violation}.
     *
     * <p>LOCATION is checked against {@code brandId} as well as {@code
     * tenantId}: a location belongs to exactly one brand (V0003's own {@code
     * fk_locations_brand_scope}), so a same-tenant sibling brand's location
     * id is a real row that a tenant-only check cannot tell apart from this
     * brand's own — and {@link uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore#accrualRule}
     * resolves by this rule's own {@code brand_id}, so a rule scoped to
     * another brand's location can never match a real order either way; it
     * would just fail silently, which is exactly the bug this whole check
     * exists to catch. CHANNEL stays tenant-only: {@code tenant.sales_channels}
     * has no brand_id column — a channel is deliberately tenant-level.
     */
    private void validateAccrualScope(UUID tenantId, UUID brandId, AccrualRuleDraft draft) {
        // validateAccrualDraft already refused a LOCATION/CHANNEL rule
        // carrying a null scopeId, so a non-null read here is a re-statement
        // of that check, not a new assumption.
        if ("LOCATION".equals(draft.scopeType())) {
            UUID scopeId = Objects.requireNonNull(draft.scopeId(), "shape-checked: a LOCATION rule names a scopeId");
            if (!store.locationExists(tenantId, brandId, scopeId)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "No location %s belongs to this brand; a LOCATION rule must scope to a real location of it"
                                .formatted(scopeId));
            }
        }
        if ("CHANNEL".equals(draft.scopeType())) {
            UUID scopeId = Objects.requireNonNull(draft.scopeId(), "shape-checked: a CHANNEL rule names a scopeId");
            if (!store.channelExists(tenantId, scopeId)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "No sales channel %s belongs to this tenant; a CHANNEL rule must scope to a real channel"
                                .formatted(scopeId));
            }
        }
    }

    // --------------------------------------------------------- redemption

    /** @param validFrom when this policy starts applying, or null for "now" */
    public record RedemptionPolicyDraft(
            int maxShareBasisPoints,
            long minOrderMinor,
            boolean excludesDeliveryFee,
            List<String> allowedChannels,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    @Transactional(readOnly = true)
    public List<RedemptionPolicyAuthoringRow> listRedemptionPolicies(UUID tenantId, UUID brandId) {
        return store.listRedemptionPolicies(tenantId, brandId);
    }

    @Transactional
    public RedemptionPolicyAuthoringRow draftRedemptionPolicy(
            UUID tenantId, UUID brandId, RedemptionPolicyDraft draft) {
        validateRedemptionDraft(draft);
        Instant now = clock.instant();
        Instant validFrom = draft.validFrom() != null ? draft.validFrom() : now;
        UUID id = UUID.randomUUID();
        store.insertRedemptionPolicyDraft(
                id,
                tenantId,
                brandId,
                draft.maxShareBasisPoints(),
                draft.minOrderMinor(),
                draft.excludesDeliveryFee(),
                draft.allowedChannels(),
                validFrom,
                draft.validUntil(),
                now);
        return new RedemptionPolicyAuthoringRow(
                id,
                draft.maxShareBasisPoints(),
                draft.minOrderMinor(),
                draft.excludesDeliveryFee(),
                draft.allowedChannels(),
                "DRAFT",
                1,
                validFrom,
                draft.validUntil());
    }

    @Transactional
    public void activateRedemptionPolicy(UUID tenantId, UUID brandId, UUID policyId) {
        RedemptionPolicyAuthoringRow policy = store.findRedemptionPolicyById(tenantId, brandId, policyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such redemption policy"));
        if (!"DRAFT".equals(policy.status())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only a DRAFT policy can be activated; this one is " + policy.status());
        }
        Instant now = clock.instant();
        if (store.activateRedemptionPolicy(tenantId, brandId, policyId, now) != 1) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This policy was activated or retired by someone else");
        }
    }

    @Transactional
    public void retireRedemptionPolicy(UUID tenantId, UUID brandId, UUID policyId) {
        if (store.retireRedemptionPolicy(tenantId, brandId, policyId, clock.instant()) != 1) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such policy to retire");
        }
    }

    private void validateRedemptionDraft(RedemptionPolicyDraft draft) {
        List<String> problems = new ArrayList<>();
        // The upper bound is 9000, not 10000: points may never cover the whole
        // order, because a zero-consideration order has no fiscal path and no
        // cash for a courier to collect (ADR 0046's redemption-cap invariant).
        if (draft.maxShareBasisPoints() <= 0 || draft.maxShareBasisPoints() > 9_000) {
            problems.add("maxShareBasisPoints must be between 1 and 9000");
        }
        if (draft.minOrderMinor() < 0) {
            problems.add("minOrderMinor cannot be negative");
        }
        if (draft.validUntil() != null
                && draft.validFrom() != null
                && !draft.validUntil().isAfter(draft.validFrom())) {
            problems.add("validUntil must be after validFrom");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
    }
}
