package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ActionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ConditionDefinition;

/**
 * Authoring, definition history, limits and the redemption ledger for the
 * automatic promotion engine (ADR 0140).
 *
 * <p>Split from {@link JdbcPromoCodeStore}, which keeps the promo-code face of the
 * same {@code pricing.promotions} table: this class owns what an automatic
 * promotion adds -- a lifecycle with a validator, immutable definition versions,
 * per-promotion limits claimed with the same two conditional writes ADR 0072 uses
 * for coupons, and the one-row-per-(order, promotion) ledger report 7.9 is built
 * from. Every statement carries the tenant, and every promotion statement the
 * brand, so a promotion, a ledger row or a definition version never resolves
 * across a tenant or a brand.
 */
@Repository
public class JdbcPromotionStore {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPromotionStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------- authoring

    /** A new DRAFT promotion at definition version 1, with its conditions and actions. */
    public void insertDraft(UUID id, UUID tenantId, UUID brandId, PromotionDefinition definition, Instant now) {
        jdbc.sql("""
                INSERT INTO pricing.promotions (
                    id, tenant_id, brand_id, code, name, kind, scope, stacking_group, exclusive, priority,
                    requires_coupon, maximum_discount_minor, currency, valid_from, valid_until,
                    maximum_redemptions, maximum_per_customer, loyalty_accrual, loyalty_redemption,
                    status, definition_version, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :code, :name, :kind, :scope, :stackingGroup, :exclusive, :priority,
                    :requiresCoupon, :maxDiscount, :currency, :validFrom, :validUntil,
                    :maxRedemptions, :maxPerCustomer, :loyaltyAccrual, :loyaltyRedemption,
                    'DRAFT', 1, 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", definition.code())
                .param("name", definition.name())
                .param("kind", definition.kind().name())
                .param("scope", definition.scope().name())
                .param("stackingGroup", definition.stackingGroup())
                .param("exclusive", definition.exclusive())
                .param("priority", definition.priority())
                .param("requiresCoupon", definition.requiresCoupon())
                .param("maxDiscount", definition.maximumDiscountMinor())
                .param("currency", definition.currency())
                .param("validFrom", utc(definition.validFrom() == null ? now : definition.validFrom()))
                .param("validUntil", utc(definition.validUntil()))
                .param("maxRedemptions", definition.maximumRedemptions())
                .param("maxPerCustomer", definition.maximumPerCustomer())
                .param("loyaltyAccrual", definition.loyaltyAccrual().name())
                .param("loyaltyRedemption", definition.loyaltyRedemption().name())
                .param("now", utc(now))
                .update();
        insertChildren(id, tenantId, brandId, definition);
    }

    /**
     * Replaces the definition of a promotion and moves it to DRAFT, in one
     * conditional update keyed on the expected row version.
     *
     * @param newDefinitionVersion the definition version the edited rule carries; the
     *        caller bumps it when a recorded version exists for the one being left
     * @return false when the row version moved on or the promotion is not editable
     */
    public boolean replaceDefinition(
            UUID tenantId,
            UUID brandId,
            UUID id,
            int expectedVersion,
            PromotionDefinition definition,
            int newDefinitionVersion,
            Instant now) {
        int updated = jdbc.sql("""
                UPDATE pricing.promotions
                SET code = :code, name = :name, kind = :kind, scope = :scope, stacking_group = :stackingGroup,
                    exclusive = :exclusive, priority = :priority, requires_coupon = :requiresCoupon,
                    maximum_discount_minor = :maxDiscount, currency = :currency,
                    valid_from = :validFrom, valid_until = :validUntil,
                    maximum_redemptions = :maxRedemptions, maximum_per_customer = :maxPerCustomer,
                    loyalty_accrual = :loyaltyAccrual, loyalty_redemption = :loyaltyRedemption,
                    status = 'DRAFT', validated_at = NULL, activated_at = NULL, activated_by = NULL, approval_id = NULL,
                    definition_version = :definitionVersion, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND version = :expectedVersion
                  AND status IN ('DRAFT', 'VALIDATED', 'SUSPENDED')
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("code", definition.code())
                .param("name", definition.name())
                .param("kind", definition.kind().name())
                .param("scope", definition.scope().name())
                .param("stackingGroup", definition.stackingGroup())
                .param("exclusive", definition.exclusive())
                .param("priority", definition.priority())
                .param("requiresCoupon", definition.requiresCoupon())
                .param("maxDiscount", definition.maximumDiscountMinor())
                .param("currency", definition.currency())
                .param("validFrom", utc(definition.validFrom() == null ? now : definition.validFrom()))
                .param("validUntil", utc(definition.validUntil()))
                .param("maxRedemptions", definition.maximumRedemptions())
                .param("maxPerCustomer", definition.maximumPerCustomer())
                .param("loyaltyAccrual", definition.loyaltyAccrual().name())
                .param("loyaltyRedemption", definition.loyaltyRedemption().name())
                .param("definitionVersion", newDefinitionVersion)
                .param("now", utc(now))
                .update();
        if (updated != 1) {
            return false;
        }
        jdbc.sql("DELETE FROM pricing.promotion_conditions WHERE tenant_id = :tenantId AND promotion_id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .update();
        jdbc.sql("DELETE FROM pricing.promotion_actions WHERE tenant_id = :tenantId AND promotion_id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .update();
        insertChildren(id, tenantId, brandId, definition);
        return true;
    }

    private void insertChildren(UUID id, UUID tenantId, UUID brandId, PromotionDefinition definition) {
        for (ConditionDefinition condition : definition.conditions()) {
            jdbc.sql("""
                    INSERT INTO pricing.promotion_conditions (promotion_id, sequence, tenant_id, brand_id, condition_type, attributes_json)
                    VALUES (:promotionId, :sequence, :tenantId, :brandId, :type, CAST(:attributes AS jsonb))
                    """)
                    .param("promotionId", id)
                    .param("sequence", condition.sequence())
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("type", condition.type().name())
                    .param("attributes", objectMapper.writeValueAsString(condition.operands()))
                    .update();
        }
        for (ActionDefinition action : definition.actions()) {
            jdbc.sql("""
                    INSERT INTO pricing.promotion_actions (promotion_id, sequence, tenant_id, brand_id, action_type, attributes_json)
                    VALUES (:promotionId, :sequence, :tenantId, :brandId, :type, CAST(:attributes AS jsonb))
                    """)
                    .param("promotionId", id)
                    .param("sequence", action.sequence())
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("type", action.type().name())
                    .param("attributes", objectMapper.writeValueAsString(action.operands()))
                    .update();
        }
    }

    /**
     * One lifecycle step, guarded by the status it leaves and the row version the
     * caller read.
     *
     * @return false when the promotion was not in {@code from} at {@code expectedVersion}
     */
    public boolean transition(
            UUID tenantId,
            UUID brandId,
            UUID id,
            int expectedVersion,
            Collection<String> from,
            String to,
            Instant now) {
        return jdbc.sql("""
                UPDATE pricing.promotions
                SET status = :to, version = version + 1, updated_at = :now,
                    validated_at = CASE WHEN :to = 'VALIDATED' THEN :now ELSE validated_at END
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND version = :expectedVersion
                  AND status = ANY(:from)
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("from", from.toArray(String[]::new))
                        .param("to", to)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** VALIDATED to ACTIVE, recording who activated it and the approval that allowed it. */
    public boolean activate(
            UUID tenantId,
            UUID brandId,
            UUID id,
            int expectedVersion,
            String activatedBy,
            @Nullable UUID approvalId,
            Instant now) {
        return jdbc.sql("""
                UPDATE pricing.promotions
                SET status = 'ACTIVE', activated_at = :now, activated_by = :activatedBy, approval_id = :approvalId,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND version = :expectedVersion
                  AND status = 'VALIDATED'
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .param("activatedBy", activatedBy)
                        .param("approvalId", approvalId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Sets one promotion's priority; the caller bumps the definition version beside it. */
    public boolean reprioritise(
            UUID tenantId, UUID brandId, UUID id, int priority, int newDefinitionVersion, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.promotions
                SET priority = :priority, definition_version = :definitionVersion, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND status <> 'ARCHIVED'
                  AND requires_coupon = false
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", id)
                        .param("priority", priority)
                        .param("definitionVersion", newDefinitionVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public Optional<PromotionRow> find(UUID tenantId, UUID brandId, UUID id) {
        List<PromotionRow> rows = rows(
                "WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.id = :id",
                Map.of("tenantId", tenantId, "brandId", brandId, "id", id));
        return rows.stream().findFirst();
    }

    /** Every automatic promotion and markup of the brand, newest first. Promo codes have their own list. */
    public List<PromotionRow> list(UUID tenantId, UUID brandId) {
        return rows(
                "WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.requires_coupon = false "
                        + "ORDER BY p.created_at DESC, p.id",
                Map.of("tenantId", tenantId, "brandId", brandId));
    }

    private List<PromotionRow> rows(String tail, Map<String, Object> params) {
        Map<UUID, List<ConditionDefinition>> conditions = new HashMap<>();
        Map<UUID, List<ActionDefinition>> actions = new HashMap<>();
        UUID tenantId = (UUID) params.get("tenantId");

        List<PromotionRow> bases = jdbc.sql("""
                SELECT p.id, p.tenant_id, p.brand_id, p.code, p.name, p.kind, p.scope, p.stacking_group, p.exclusive, p.priority,
                       p.requires_coupon, p.maximum_discount_minor, p.currency, p.valid_from, p.valid_until,
                       p.maximum_redemptions, p.maximum_per_customer, p.loyalty_accrual, p.loyalty_redemption,
                       p.status, p.definition_version, p.version, p.consumed_count, p.validated_at, p.activated_at,
                       p.activated_by, p.approval_id, p.created_at, p.updated_at
                FROM pricing.promotions p
                """ + tail)
                .params(params)
                .query((row, n) -> new PromotionRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        new PromotionDefinition(
                                row.getString("code"),
                                row.getString("name"),
                                Promotion.Kind.valueOf(row.getString("kind")),
                                Promotion.Scope.valueOf(row.getString("scope")),
                                row.getString("stacking_group"),
                                row.getBoolean("exclusive"),
                                row.getInt("priority"),
                                row.getBoolean("requires_coupon"),
                                row.getObject("maximum_discount_minor", Long.class),
                                row.getString("currency"),
                                instant(row.getObject("valid_from", OffsetDateTime.class)),
                                instant(row.getObject("valid_until", OffsetDateTime.class)),
                                row.getObject("maximum_redemptions", Integer.class),
                                row.getObject("maximum_per_customer", Integer.class),
                                Promotion.LoyaltyAccrual.valueOf(row.getString("loyalty_accrual")),
                                Promotion.LoyaltyRedemption.valueOf(row.getString("loyalty_redemption")),
                                List.of(),
                                List.of()),
                        row.getString("status"),
                        row.getInt("definition_version"),
                        row.getInt("version"),
                        row.getInt("consumed_count"),
                        instant(row.getObject("validated_at", OffsetDateTime.class)),
                        instant(row.getObject("activated_at", OffsetDateTime.class)),
                        row.getString("activated_by"),
                        row.getObject("approval_id", UUID.class),
                        requiredInstant(row.getObject("created_at", OffsetDateTime.class)),
                        requiredInstant(row.getObject("updated_at", OffsetDateTime.class))))
                .list();
        if (bases.isEmpty()) {
            return List.of();
        }
        UUID[] ids = bases.stream().map(PromotionRow::id).toArray(UUID[]::new);
        jdbc.sql("""
                SELECT promotion_id, sequence, condition_type, attributes_json::text AS attributes
                FROM pricing.promotion_conditions
                WHERE tenant_id = :tenantId AND promotion_id = ANY(:ids) ORDER BY promotion_id, sequence
                """)
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query((row, n) -> {
                    conditions
                            .computeIfAbsent(row.getObject("promotion_id", UUID.class), k -> new ArrayList<>())
                            .add(new ConditionDefinition(
                                    row.getInt("sequence"),
                                    Promotion.Condition.Type.valueOf(row.getString("condition_type")),
                                    readDocument(row.getString("attributes"))));
                    return 0;
                })
                .list();
        jdbc.sql("""
                SELECT promotion_id, sequence, action_type, attributes_json::text AS attributes
                FROM pricing.promotion_actions
                WHERE tenant_id = :tenantId AND promotion_id = ANY(:ids) ORDER BY promotion_id, sequence
                """)
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query((row, n) -> {
                    actions.computeIfAbsent(row.getObject("promotion_id", UUID.class), k -> new ArrayList<>())
                            .add(new ActionDefinition(
                                    row.getInt("sequence"),
                                    Promotion.Action.Type.valueOf(row.getString("action_type")),
                                    readDocument(row.getString("attributes"))));
                    return 0;
                })
                .list();
        return bases.stream()
                .map(base -> base.withChildren(
                        conditions.getOrDefault(base.id(), List.of()), actions.getOrDefault(base.id(), List.of())))
                .toList();
    }

    // ----------------------------------------------------- definition history

    /** Appends the definition at this version; a second write of the same version is a no-op. */
    public void appendDefinitionVersion(
            UUID tenantId,
            UUID brandId,
            UUID id,
            int definitionVersion,
            PromotionDefinition definition,
            String recordedBy,
            String reason,
            Instant now) {
        jdbc.sql("""
                INSERT INTO pricing.promotion_definition_versions (
                    promotion_id, definition_version, tenant_id, brand_id, definition, recorded_at, recorded_by, reason)
                VALUES (:id, :version, :tenantId, :brandId, CAST(:definition AS jsonb), :now, :recordedBy, :reason)
                ON CONFLICT (promotion_id, definition_version) DO NOTHING
                """)
                .param("id", id)
                .param("version", definitionVersion)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("definition", objectMapper.writeValueAsString(definition.canonical()))
                .param("recordedBy", recordedBy)
                .param("reason", reason)
                .param("now", utc(now))
                .update();
    }

    public boolean hasDefinitionVersion(UUID tenantId, UUID id, int definitionVersion) {
        return jdbc.sql("""
                SELECT 1 FROM pricing.promotion_definition_versions
                WHERE tenant_id = :tenantId AND promotion_id = :id AND definition_version = :version
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("version", definitionVersion)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public List<DefinitionVersionRow> definitionVersions(UUID tenantId, UUID brandId, UUID id) {
        return jdbc.sql("""
                SELECT definition_version, definition::text AS definition, recorded_at, recorded_by, reason
                FROM pricing.promotion_definition_versions
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND promotion_id = :id
                ORDER BY definition_version DESC
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", id)
                .query((row, n) -> new DefinitionVersionRow(
                        row.getInt("definition_version"),
                        PromotionDefinition.fromCanonical(readDocument(row.getString("definition"))),
                        requiredInstant(row.getObject("recorded_at", OffsetDateTime.class)),
                        row.getString("recorded_by"),
                        row.getString("reason")))
                .list();
    }

    /** The definition as it was at {@code version}, or empty when none was recorded. */
    public Optional<PromotionDefinition> definitionAt(UUID tenantId, UUID brandId, UUID id, int version) {
        return jdbc.sql("""
                SELECT definition::text AS definition FROM pricing.promotion_definition_versions
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND promotion_id = :id AND definition_version = :version
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", id)
                .param("version", version)
                .query((row, n) -> PromotionDefinition.fromCanonical(readDocument(row.getString("definition"))))
                .optional();
    }

    // ------------------------------------------------------------------ limits

    /**
     * Claims one redemption against a limited promotion's total. The only place
     * {@code consumed_count} moves up: a conditional {@code UPDATE}, so the row lock
     * serialises concurrent checkouts and exactly one wins the last slot.
     */
    public boolean claimTotal(UUID tenantId, UUID brandId, UUID promotionId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.promotions
                SET consumed_count = consumed_count + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND status = 'ACTIVE'
                  AND (maximum_redemptions IS NULL OR consumed_count < maximum_redemptions)
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", promotionId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Compensates {@link #claimTotal} when a later step of the same checkout fails. */
    public void releaseTotal(UUID tenantId, UUID brandId, UUID promotionId) {
        jdbc.sql("""
                UPDATE pricing.promotions SET consumed_count = consumed_count - 1
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND consumed_count > 0
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", promotionId)
                .update();
    }

    /** One of this customer's slots, claimed atomically; the mirror of the coupon upsert. */
    public boolean claimCustomerSlot(
            UUID tenantId, UUID brandId, UUID promotionId, UUID customerAccountId, int maximumPerCustomer) {
        return jdbc.sql("""
                INSERT INTO pricing.promotion_customer_usage (
                    promotion_id, tenant_id, brand_id, customer_account_id, consumed_count, maximum_per_customer)
                VALUES (:promotionId, :tenantId, :brandId, :customerId, 1, :maxPerCustomer)
                ON CONFLICT (promotion_id, customer_account_id) DO UPDATE
                SET consumed_count = pricing.promotion_customer_usage.consumed_count + 1
                WHERE pricing.promotion_customer_usage.consumed_count < pricing.promotion_customer_usage.maximum_per_customer
                """)
                        .param("promotionId", promotionId)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("customerId", customerAccountId)
                        .param("maxPerCustomer", maximumPerCustomer)
                        .update()
                == 1;
    }

    public void releaseCustomerSlot(UUID tenantId, UUID promotionId, UUID customerAccountId) {
        jdbc.sql("""
                UPDATE pricing.promotion_customer_usage SET consumed_count = consumed_count - 1
                WHERE tenant_id = :tenantId AND promotion_id = :promotionId AND customer_account_id = :customerId
                  AND consumed_count > 0
                """)
                .param("tenantId", tenantId)
                .param("promotionId", promotionId)
                .param("customerId", customerAccountId)
                .update();
    }

    /**
     * Which limited promotions have nothing left for this customer, read fresh at
     * every price. A read only: the claim at checkout is what decides, and it
     * re-checks inside its own transaction.
     */
    public List<UUID> limitReached(UUID tenantId, UUID brandId, @Nullable UUID customerAccountId) {
        return jdbc.sql("""
                SELECT p.id
                FROM pricing.promotions p
                LEFT JOIN pricing.promotion_customer_usage u
                       ON u.promotion_id = p.id AND u.tenant_id = p.tenant_id AND u.customer_account_id = :customerId
                WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.status = 'ACTIVE' AND p.requires_coupon = false
                  AND (p.maximum_redemptions IS NOT NULL OR p.maximum_per_customer IS NOT NULL)
                  AND ((p.maximum_redemptions IS NOT NULL AND p.consumed_count >= p.maximum_redemptions)
                    OR (p.maximum_per_customer IS NOT NULL AND u.consumed_count >= p.maximum_per_customer))
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerId", customerAccountId)
                .query(UUID.class)
                .list();
    }

    // ---------------------------------------------------- payment-method input

    /** Whether the brand has a live promotion that reads the payment method. */
    public boolean hasActivePaymentMethodPromotion(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pricing.promotions p
                    JOIN pricing.promotion_conditions c
                      ON c.promotion_id = p.id AND c.tenant_id = p.tenant_id AND c.condition_type = 'PAYMENT_METHOD'
                    WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.status = 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Whether a promotion the order holds, at the definition version it holds it
     * under, reads the payment method. Judged on the recorded version rather than the
     * current conditions, because that is the rule the order is repriced under.
     */
    public boolean orderHoldsPaymentMethodPromotion(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pricing.promotion_redemptions r
                    JOIN pricing.promotion_definition_versions v
                      ON v.promotion_id = r.promotion_id AND v.definition_version = r.definition_version
                    WHERE r.tenant_id = :tenantId AND r.order_id = :orderId AND r.status = 'REDEEMED'
                      AND position('PAYMENT_METHOD' in v.definition::text) > 0)
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(Boolean.class)
                .single();
    }

    // ------------------------------------------------------------------ ledger

    /** Writes the ledger row for a promotion an order carries; a retried checkout finds its own row and writes nothing. */
    public boolean insertRedemption(NewRedemption redemption, Instant now) {
        return jdbc.sql("""
                INSERT INTO pricing.promotion_redemptions (
                    id, tenant_id, brand_id, promotion_id, definition_version, claimed_quote_id, current_quote_id,
                    last_revision, order_id, customer_account_id, discount_minor, markup_minor, currency,
                    status, redeemed_at)
                VALUES (:id, :tenantId, :brandId, :promotionId, :definitionVersion, :quoteId, :quoteId,
                    :revision, :orderId, :customerId, :discount, :markup, :currency, 'REDEEMED', :now)
                ON CONFLICT (tenant_id, promotion_id, claimed_quote_id) DO NOTHING
                """)
                        .param("id", redemption.id())
                        .param("tenantId", redemption.tenantId())
                        .param("brandId", redemption.brandId())
                        .param("promotionId", redemption.promotionId())
                        .param("definitionVersion", redemption.definitionVersion())
                        .param("quoteId", redemption.quoteId())
                        .param("revision", redemption.revision())
                        .param("orderId", redemption.orderId())
                        .param("customerId", redemption.customerAccountId())
                        .param("discount", redemption.discountMinor())
                        .param("markup", redemption.markupMinor())
                        .param("currency", redemption.currency())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** The REDEEMED rows an order holds, for an amendment to price against. */
    public List<LedgerRow> heldByOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, brand_id, promotion_id, definition_version, claimed_quote_id, current_quote_id,
                       last_revision, order_id, customer_account_id, discount_minor, markup_minor, currency, status
                FROM pricing.promotion_redemptions
                WHERE tenant_id = :tenantId AND order_id = :orderId
                ORDER BY promotion_id
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcPromotionStore::mapLedger)
                .list();
    }

    public List<LedgerRow> byClaimedQuote(UUID tenantId, UUID quoteId) {
        return jdbc.sql("""
                SELECT id, brand_id, promotion_id, definition_version, claimed_quote_id, current_quote_id,
                       last_revision, order_id, customer_account_id, discount_minor, markup_minor, currency, status
                FROM pricing.promotion_redemptions
                WHERE tenant_id = :tenantId AND claimed_quote_id = :quoteId AND status = 'REDEEMED'
                ORDER BY promotion_id
                """)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query(JdbcPromotionStore::mapLedger)
                .list();
    }

    /** Moves a ledger row in place when an amendment reprices; the claimed quote never changes. */
    public boolean restate(
            UUID tenantId,
            UUID orderId,
            UUID promotionId,
            UUID currentQuoteId,
            int revision,
            long discountMinor,
            long markupMinor) {
        return jdbc.sql("""
                UPDATE pricing.promotion_redemptions
                SET current_quote_id = :quoteId, last_revision = :revision,
                    discount_minor = :discount, markup_minor = :markup
                WHERE tenant_id = :tenantId AND order_id = :orderId AND promotion_id = :promotionId AND status = 'REDEEMED'
                """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .param("promotionId", promotionId)
                        .param("quoteId", currentQuoteId)
                        .param("revision", revision)
                        .param("discount", discountMinor)
                        .param("markup", markupMinor)
                        .update()
                == 1;
    }

    /** A promotion that stopped applying to an amended order: the row is RELEASED, the counter stays consumed. */
    public boolean markReleased(UUID tenantId, UUID orderId, UUID promotionId, int revision, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.promotion_redemptions
                SET status = 'RELEASED', released_at = :now, last_revision = :revision
                WHERE tenant_id = :tenantId AND order_id = :orderId AND promotion_id = :promotionId AND status = 'REDEEMED'
                """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .param("promotionId", promotionId)
                        .param("revision", revision)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** A promotion that newly applies to an amended order: a REDEEMED row that was RELEASED earlier comes back. */
    public boolean reinstate(
            UUID tenantId,
            UUID orderId,
            UUID promotionId,
            UUID currentQuoteId,
            int revision,
            long discountMinor,
            long markupMinor) {
        return jdbc.sql("""
                UPDATE pricing.promotion_redemptions
                SET status = 'REDEEMED', released_at = NULL, current_quote_id = :quoteId, last_revision = :revision,
                    discount_minor = :discount, markup_minor = :markup
                WHERE tenant_id = :tenantId AND order_id = :orderId AND promotion_id = :promotionId AND status = 'RELEASED'
                """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .param("promotionId", promotionId)
                        .param("quoteId", currentQuoteId)
                        .param("revision", revision)
                        .param("discount", discountMinor)
                        .param("markup", markupMinor)
                        .update()
                == 1;
    }

    /** Gives back every claim a checkout took for this quote when a later step of the same transaction fails. */
    public List<LedgerRow> deleteByClaimedQuote(UUID tenantId, UUID quoteId) {
        List<LedgerRow> rows = byClaimedQuote(tenantId, quoteId);
        jdbc.sql("""
                DELETE FROM pricing.promotion_redemptions
                WHERE tenant_id = :tenantId AND claimed_quote_id = :quoteId AND status = 'REDEEMED'
                """).param("tenantId", tenantId).param("quoteId", quoteId).update();
        return rows;
    }

    /**
     * What each promotion took off, or added, on one quote, read from the quote's
     * own adjustments (never from a value a caller supplies). Automatic promotions
     * only: a coupon-gated one is accounted for by {@link JdbcPromoCodeStore}.
     */
    public List<QuotePromotionAmount> promotionAmountsOnQuote(UUID tenantId, UUID quoteId) {
        return jdbc.sql("""
                SELECT qa.source_id AS promotion_id, qa.source_version AS definition_version, q.currency,
                       COALESCE(SUM(CASE WHEN qa.amount_minor < 0 THEN -qa.amount_minor ELSE 0 END), 0) AS discount_minor,
                       COALESCE(SUM(CASE WHEN qa.adjustment_type = 'ITEM_MARKUP' THEN qa.amount_minor ELSE 0 END), 0) AS markup_minor
                FROM pricing.quote_adjustments qa
                JOIN pricing.quotes q ON q.id = qa.quote_id AND q.tenant_id = qa.tenant_id
                JOIN pricing.promotions p ON p.id = qa.source_id AND p.tenant_id = qa.tenant_id
                WHERE qa.tenant_id = :tenantId AND qa.quote_id = :quoteId AND qa.source_type = 'PROMOTION'
                  AND p.requires_coupon = false
                GROUP BY qa.source_id, qa.source_version, q.currency
                ORDER BY qa.source_id
                """)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query((row, n) -> new QuotePromotionAmount(
                        row.getObject("promotion_id", UUID.class),
                        row.getInt("definition_version"),
                        row.getString("currency"),
                        row.getLong("discount_minor"),
                        row.getLong("markup_minor")))
                .list();
    }

    /** The bounded redemptions drill-down for one promotion: ids and amounts only, never a name or a contact. */
    public List<LedgerRow> redemptionsForPromotion(UUID tenantId, UUID brandId, UUID promotionId, int limit) {
        return jdbc.sql("""
                SELECT id, brand_id, promotion_id, definition_version, claimed_quote_id, current_quote_id,
                       last_revision, order_id, customer_account_id, discount_minor, markup_minor, currency, status
                FROM pricing.promotion_redemptions
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND promotion_id = :promotionId
                ORDER BY redeemed_at DESC, id
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("promotionId", promotionId)
                .param("limit", limit)
                .query(JdbcPromotionStore::mapLedger)
                .list();
    }

    /** Ledger and coupon rows redeemed in [from, to), the source of the day-close fact. */
    public List<RedemptionFactRow> redemptionFactRows(UUID tenantId, Instant from, Instant to) {
        List<RedemptionFactRow> rows = new ArrayList<>(jdbc.sql("""
                SELECT r.id, r.brand_id, r.promotion_id, p.code, r.definition_version, r.order_id,
                       r.customer_account_id, r.discount_minor, r.markup_minor, r.currency, r.redeemed_at
                FROM pricing.promotion_redemptions r
                JOIN pricing.promotions p ON p.id = r.promotion_id AND p.tenant_id = r.tenant_id
                WHERE r.tenant_id = :tenantId AND r.status = 'REDEEMED' AND r.redeemed_at >= :from AND r.redeemed_at < :to
                ORDER BY r.redeemed_at, r.id
                """)
                .param("tenantId", tenantId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((row, n) -> new RedemptionFactRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("promotion_id", UUID.class),
                        row.getString("code"),
                        row.getInt("definition_version"),
                        "AUTOMATIC",
                        null,
                        row.getObject("order_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getLong("discount_minor"),
                        row.getLong("markup_minor"),
                        row.getString("currency"),
                        requiredInstant(row.getObject("redeemed_at", OffsetDateTime.class))))
                .list());
        rows.addAll(jdbc.sql("""
                SELECT r.id, r.brand_id, r.promotion_id, r.coupon_id, p.code, p.definition_version, r.order_id,
                       r.customer_account_id, r.amount_minor, r.currency, r.redeemed_at
                FROM pricing.coupon_redemptions r
                JOIN pricing.promotions p ON p.id = r.promotion_id AND p.tenant_id = r.tenant_id
                WHERE r.tenant_id = :tenantId AND r.status = 'REDEEMED' AND r.order_id IS NOT NULL
                  AND r.redeemed_at >= :from AND r.redeemed_at < :to
                ORDER BY r.redeemed_at, r.id
                """)
                .param("tenantId", tenantId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((row, n) -> new RedemptionFactRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("promotion_id", UUID.class),
                        // The coupon word is the promotion's handle for a promo code (ADR 0072) and
                        // is a bearer secret: it never reaches a fact. The handle is replaced by the
                        // promotion id's text so the column stays non-empty and meaningless.
                        "COUPON",
                        row.getInt("definition_version"),
                        "COUPON",
                        row.getObject("coupon_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getLong("amount_minor"),
                        0L,
                        row.getString("currency"),
                        requiredInstant(row.getObject("redeemed_at", OffsetDateTime.class))))
                .list());
        return rows;
    }

    private static LedgerRow mapLedger(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new LedgerRow(
                row.getObject("id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("promotion_id", UUID.class),
                row.getInt("definition_version"),
                row.getObject("claimed_quote_id", UUID.class),
                row.getObject("current_quote_id", UUID.class),
                row.getInt("last_revision"),
                row.getObject("order_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getLong("discount_minor"),
                row.getLong("markup_minor"),
                row.getString("currency"),
                row.getString("status"));
    }

    private Map<String, Object> readDocument(@Nullable String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, DOCUMENT);
    }

    private static Instant requiredInstant(@Nullable OffsetDateTime value) {
        return java.util.Objects.requireNonNull(value, "A non-null timestamp column")
                .toInstant();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ values

    /** One promotion as stored, definition and lifecycle together. */
    public record PromotionRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            PromotionDefinition definition,
            String status,
            int definitionVersion,
            int version,
            int consumedCount,
            @Nullable Instant validatedAt,
            @Nullable Instant activatedAt,
            @Nullable String activatedBy,
            @Nullable UUID approvalId,
            Instant createdAt,
            Instant updatedAt) {

        PromotionRow withChildren(List<ConditionDefinition> conditions, List<ActionDefinition> actions) {
            PromotionDefinition d = definition;
            return new PromotionRow(
                    id,
                    tenantId,
                    brandId,
                    new PromotionDefinition(
                            d.code(),
                            d.name(),
                            d.kind(),
                            d.scope(),
                            d.stackingGroup(),
                            d.exclusive(),
                            d.priority(),
                            d.requiresCoupon(),
                            d.maximumDiscountMinor(),
                            d.currency(),
                            d.validFrom(),
                            d.validUntil(),
                            d.maximumRedemptions(),
                            d.maximumPerCustomer(),
                            d.loyaltyAccrual(),
                            d.loyaltyRedemption(),
                            conditions,
                            actions),
                    status,
                    definitionVersion,
                    version,
                    consumedCount,
                    validatedAt,
                    activatedAt,
                    activatedBy,
                    approvalId,
                    createdAt,
                    updatedAt);
        }
    }

    public record DefinitionVersionRow(
            int definitionVersion,
            PromotionDefinition definition,
            Instant recordedAt,
            String recordedBy,
            String reason) {}

    public record NewRedemption(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID promotionId,
            int definitionVersion,
            UUID quoteId,
            int revision,
            UUID orderId,
            @Nullable UUID customerAccountId,
            long discountMinor,
            long markupMinor,
            String currency) {}

    public record LedgerRow(
            UUID id,
            UUID brandId,
            UUID promotionId,
            int definitionVersion,
            UUID claimedQuoteId,
            UUID currentQuoteId,
            int lastRevision,
            UUID orderId,
            @Nullable UUID customerAccountId,
            long discountMinor,
            long markupMinor,
            String currency,
            String status) {}

    public record QuotePromotionAmount(
            UUID promotionId, int definitionVersion, String currency, long discountMinor, long markupMinor) {}

    public record RedemptionFactRow(
            UUID redemptionId,
            UUID brandId,
            UUID promotionId,
            String promotionCode,
            int definitionVersion,
            String sourceKind,
            @Nullable UUID couponId,
            UUID orderId,
            @Nullable UUID customerAccountId,
            long discountMinor,
            long markupMinor,
            String currency,
            Instant redeemedAt) {}
}
