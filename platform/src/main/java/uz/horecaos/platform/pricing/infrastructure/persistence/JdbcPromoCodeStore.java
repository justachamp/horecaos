package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.pricing.domain.Promotion;

/**
 * Promotion, promo-code and redemption persistence (ADR 0072).
 *
 * <p><strong>V0093 already built this schema.</strong> {@code pricing.promotions},
 * {@code promotion_conditions}, {@code promotion_actions}, {@code coupon_codes},
 * {@code coupon_customer_usage} and {@code coupon_redemptions} were created by an
 * earlier wave implementing ADR 0018's coupon design, with no authoring service
 * or controller above them — {@code marketing-shell.ts}'s own comment names
 * exactly this gap. This class is the first reader and writer either table has
 * had; nothing here alters V0093's shapes.
 *
 * <p>A coupon's redeemable code is hashed ({@code normalized_code_hash}), never
 * stored in the clear — ADR 0018's own text: "Coupon values are stored
 * encrypted where retrieval is needed and hashed for lookup." A marketer sees
 * their own code back once, in the response to the call that created it; every
 * later read shows only {@code code_hint} (the last four characters), enough to
 * recognise a row in a list and not enough to guess one.
 *
 * <p>Split from {@link JdbcPricingStore} rather than added to it: that class
 * already owns price books, tax and quotes, and this one owns a genuinely
 * separate authoring lifecycle and a genuinely separate concurrency guard.
 */
@Repository
public class JdbcPromoCodeStore {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPromoCodeStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** SHA-256 of the normalized (trimmed, upper-cased) code, hex-encoded — the only form ever stored or queried on. */
    public static String hash(String normalizedCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /** The last four characters, or the whole thing if shorter — enough to recognise a row, not enough to guess one. */
    public static String hint(String normalizedCode) {
        return normalizedCode.length() <= 4 ? normalizedCode : normalizedCode.substring(normalizedCode.length() - 4);
    }

    public static String normalize(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------- authoring

    @SuppressWarnings("checkstyle:ParameterNumber")
    public void insertPromotionDraft(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String code,
            String name,
            String scope,
            String stackingGroup,
            boolean exclusive,
            int priority,
            boolean requiresCoupon,
            @Nullable Long maximumDiscountMinor,
            String currency,
            Instant validFrom,
            @Nullable Instant validUntil,
            Instant now) {
        jdbc.sql("""
                INSERT INTO pricing.promotions (
                    id, tenant_id, brand_id, code, name, scope, stacking_group, exclusive, priority,
                    requires_coupon, maximum_discount_minor, currency, valid_from, valid_until,
                    status, definition_version, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :code, :name, :scope, :stackingGroup, :exclusive, :priority,
                    :requiresCoupon, :maxDiscount, :currency, :validFrom, :validUntil,
                    'DRAFT', 1, 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("name", name)
                .param("scope", scope)
                .param("stackingGroup", stackingGroup)
                .param("exclusive", exclusive)
                .param("priority", priority)
                .param("requiresCoupon", requiresCoupon)
                .param("maxDiscount", maximumDiscountMinor)
                .param("currency", currency)
                .param("validFrom", utc(validFrom))
                .param("validUntil", utc(validUntil))
                .param("now", utc(now))
                .update();
    }

    public void insertPromotionCondition(
            UUID promotionId,
            UUID tenantId,
            UUID brandId,
            int sequence,
            String conditionType,
            Map<String, Object> attributes) {
        jdbc.sql("""
                INSERT INTO pricing.promotion_conditions (promotion_id, sequence, tenant_id, brand_id, condition_type, attributes_json)
                VALUES (:promotionId, :sequence, :tenantId, :brandId, :type, CAST(:attributes AS jsonb))
                """)
                .param("promotionId", promotionId)
                .param("sequence", sequence)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("type", conditionType)
                .param("attributes", objectMapper.writeValueAsString(attributes))
                .update();
    }

    public void insertPromotionAction(
            UUID promotionId,
            UUID tenantId,
            UUID brandId,
            int sequence,
            String actionType,
            Map<String, Object> attributes) {
        jdbc.sql("""
                INSERT INTO pricing.promotion_actions (promotion_id, sequence, tenant_id, brand_id, action_type, attributes_json)
                VALUES (:promotionId, :sequence, :tenantId, :brandId, :type, CAST(:attributes AS jsonb))
                """)
                .param("promotionId", promotionId)
                .param("sequence", sequence)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("type", actionType)
                .param("attributes", objectMapper.writeValueAsString(attributes))
                .update();
    }

    /**
     * DRAFT to ACTIVE. Sets {@code validated_at} alongside {@code activated_at}:
     * this authoring surface's closed, pre-validated discount shapes need no
     * separate review step between the two, unlike a general rule authored
     * against the full condition/action schema might.
     *
     * @return true when a DRAFT row for this id was found and promoted
     */
    public boolean activatePromotion(UUID tenantId, UUID brandId, UUID promotionId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.promotions
                SET status = 'ACTIVE', validated_at = :now, activated_at = :now, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND status = 'DRAFT'
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", promotionId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean retirePromotion(UUID tenantId, UUID brandId, UUID promotionId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.promotions
                SET status = 'ARCHIVED', updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                  AND status IN ('DRAFT', 'VALIDATED', 'ACTIVE', 'SUSPENDED')
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", promotionId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Created {@code SUSPENDED}: {@code pricing.coupon_codes} has no {@code DRAFT}
     * state of its own, and a coupon must not be redeemable before the
     * promotion it belongs to is activated.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public void insertCouponDraft(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID promotionId,
            String normalizedCode,
            @Nullable Integer totalLimit,
            int perCustomerLimit,
            Instant validFrom,
            @Nullable Instant validUntil,
            Instant now) {
        jdbc.sql("""
                INSERT INTO pricing.coupon_codes (
                    id, tenant_id, brand_id, promotion_id, normalized_code_hash, code_hint, status,
                    maximum_redemptions, maximum_per_customer, consumed_count,
                    valid_from, valid_until, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :promotionId, :hash, :hint, 'SUSPENDED',
                    :totalLimit, :perCustomerLimit, 0, :validFrom, :validUntil, 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("promotionId", promotionId)
                .param("hash", hash(normalizedCode))
                .param("hint", hint(normalizedCode))
                .param("totalLimit", totalLimit)
                .param("perCustomerLimit", perCustomerLimit)
                .param("validFrom", utc(validFrom))
                .param("validUntil", utc(validUntil))
                .param("now", utc(now))
                .update();
    }

    public boolean activateCoupon(UUID tenantId, UUID brandId, UUID couponId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.coupon_codes
                SET status = 'ACTIVE', updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND status = 'SUSPENDED'
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", couponId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean retireCoupon(UUID tenantId, UUID brandId, UUID couponId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.coupon_codes
                SET status = 'ARCHIVED', updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                  AND status IN ('SUSPENDED', 'ACTIVE', 'EXHAUSTED')
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", couponId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * One authored promo code, joined with its single action and up to three
     * conditions.
     *
     * @param plaintextCode present only immediately after {@code draft} — the
     *                      one moment the plaintext is available at all;
     *                      absent (and rendered as {@code code_hint} by the
     *                      caller) on every later read
     */
    public Optional<PromoCodeAuthoringRow> findPromoCodeById(
            UUID tenantId, UUID brandId, UUID couponId, @Nullable String plaintextCode) {
        return promoCodeQuery(" AND cc.id = :couponId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("couponId", couponId)
                .query((row, n) -> mapAuthoringRow(row, plaintextCode))
                .optional();
    }

    /** Every promo code this brand has authored, newest first — an authoring screen needs the lineage. */
    public List<PromoCodeAuthoringRow> listPromoCodes(UUID tenantId, UUID brandId) {
        return promoCodeQuery(" ORDER BY cc.created_at DESC")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, n) -> mapAuthoringRow(row, null))
                .list();
    }

    private JdbcClient.StatementSpec promoCodeQuery(String tailClause) {
        return jdbc.sql("""
                SELECT cc.id AS coupon_id, cc.code_hint, cc.status AS coupon_status,
                       cc.maximum_redemptions, cc.maximum_per_customer, cc.consumed_count,
                       cc.valid_from, cc.valid_until, cc.version,
                       p.id AS promotion_id, p.name, p.currency, p.maximum_discount_minor,
                       p.status AS promotion_status, p.definition_version,
                       pa.action_type, pa.attributes_json::text AS action_attributes,
                       sub.attributes_json::text AS subtotal_attributes,
                       ch.attributes_json::text AS channel_attributes,
                       loc.attributes_json::text AS location_attributes
                FROM pricing.coupon_codes cc
                JOIN pricing.promotions p ON p.id = cc.promotion_id AND p.tenant_id = cc.tenant_id
                JOIN pricing.promotion_actions pa ON pa.promotion_id = p.id AND pa.tenant_id = p.tenant_id
                LEFT JOIN pricing.promotion_conditions sub
                    ON sub.promotion_id = p.id AND sub.tenant_id = p.tenant_id AND sub.condition_type = 'SUBTOTAL_AT_LEAST'
                LEFT JOIN pricing.promotion_conditions ch
                    ON ch.promotion_id = p.id AND ch.tenant_id = p.tenant_id AND ch.condition_type = 'CHANNEL'
                LEFT JOIN pricing.promotion_conditions loc
                    ON loc.promotion_id = p.id AND loc.tenant_id = p.tenant_id AND loc.condition_type = 'LOCATION'
                WHERE cc.tenant_id = :tenantId AND cc.brand_id = :brandId""" + tailClause);
    }

    @SuppressWarnings("unchecked")
    private PromoCodeAuthoringRow mapAuthoringRow(ResultSet row, @Nullable String plaintextCode) throws SQLException {
        Map<String, Object> action = readJson(row.getString("action_attributes"));
        Map<String, Object> subtotal = readJson(row.getString("subtotal_attributes"));
        Map<String, Object> channel = readJson(row.getString("channel_attributes"));
        Map<String, Object> location = readJson(row.getString("location_attributes"));

        String actionType = row.getString("action_type");
        long value =
                switch (actionType) {
                    case "ORDER_PERCENTAGE_DISCOUNT" ->
                        ((Number) Objects.requireNonNull(
                                        action.get("basisPoints"), "authored action always carries basisPoints"))
                                .longValue();
                    case "ORDER_FIXED_DISCOUNT" ->
                        ((Number) Objects.requireNonNull(
                                        action.get("amountMinor"), "authored action always carries amountMinor"))
                                .longValue();
                    default -> 0L;
                };

        Object channelList = channel.get("channels");
        List<String> channels = channelList == null ? List.of() : (List<String>) (List<?>) channelList;
        Object locationList = location.get("locationIds");
        List<UUID> locationIds = locationList == null
                ? List.of()
                : ((List<String>) (List<?>) locationList)
                        .stream().map(UUID::fromString).toList();

        Object minBasket = subtotal.get("amountMinor");
        long minBasketMinor = minBasket == null ? 0L : ((Number) minBasket).longValue();

        return new PromoCodeAuthoringRow(
                row.getObject("coupon_id", UUID.class),
                row.getObject("promotion_id", UUID.class),
                row.getString("name"),
                plaintextCode,
                row.getString("code_hint"),
                actionType,
                value,
                minBasketMinor,
                row.getObject("maximum_discount_minor", Long.class),
                row.getString("currency"),
                channels,
                locationIds,
                row.getObject("maximum_redemptions", Integer.class),
                row.getInt("maximum_per_customer"),
                row.getInt("consumed_count"),
                row.getString("coupon_status"),
                row.getInt("version"),
                row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                row.getObject("valid_until", OffsetDateTime.class) == null
                        ? null
                        : row.getObject("valid_until", OffsetDateTime.class).toInstant());
    }

    private Map<String, Object> readJson(@Nullable String json) {
        if (json == null) {
            return Map.of();
        }
        return objectMapper.readValue(json, ATTRIBUTES_TYPE);
    }

    // ------------------------------------------------------- pricing reads

    /**
     * Every {@code ACTIVE} promotion of this brand, in force right now, fully
     * reconstructed as {@link Promotion} values for {@code PricingEngine}.
     */
    public List<Promotion> listActivePromotionsForPricing(UUID tenantId, UUID brandId, Instant now) {
        List<PromotionBase> bases = jdbc.sql("""
                SELECT id, scope, stacking_group, exclusive, priority, requires_coupon,
                       maximum_discount_minor, currency, valid_from, valid_until, definition_version
                FROM pricing.promotions
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND status = 'ACTIVE'
                  AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("now", utc(now))
                .query((row, n) -> new PromotionBase(
                        row.getObject("id", UUID.class),
                        row.getString("scope"),
                        row.getString("stacking_group"),
                        row.getBoolean("exclusive"),
                        row.getInt("priority"),
                        row.getBoolean("requires_coupon"),
                        row.getObject("maximum_discount_minor", Long.class),
                        row.getString("currency"),
                        row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        row.getObject("valid_until", OffsetDateTime.class) == null
                                ? null
                                : row.getObject("valid_until", OffsetDateTime.class)
                                        .toInstant(),
                        row.getInt("definition_version")))
                .list();

        if (bases.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = bases.stream().map(PromotionBase::id).toList();

        Map<UUID, List<Promotion.Condition>> conditionsByPromotion = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT promotion_id, sequence, condition_type, attributes_json::text AS attributes
                FROM pricing.promotion_conditions
                WHERE tenant_id = :tenantId AND promotion_id = ANY(:ids)
                ORDER BY promotion_id, sequence
                """)
                .param("tenantId", tenantId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, n) -> Map.entry(
                        row.getObject("promotion_id", UUID.class),
                        new Promotion.Condition(
                                row.getInt("sequence"),
                                Promotion.Condition.Type.valueOf(row.getString("condition_type")),
                                new Promotion.Operands(readJson(row.getString("attributes"))))))
                .list()
                .forEach(entry -> conditionsByPromotion
                        .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue()));

        Map<UUID, List<Promotion.Action>> actionsByPromotion = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT promotion_id, sequence, action_type, attributes_json::text AS attributes
                FROM pricing.promotion_actions
                WHERE tenant_id = :tenantId AND promotion_id = ANY(:ids)
                ORDER BY promotion_id, sequence
                """)
                .param("tenantId", tenantId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, n) -> Map.entry(
                        row.getObject("promotion_id", UUID.class),
                        new Promotion.Action(
                                row.getInt("sequence"),
                                Promotion.Action.Type.valueOf(row.getString("action_type")),
                                new Promotion.Operands(readJson(row.getString("attributes"))))))
                .list()
                .forEach(entry -> actionsByPromotion
                        .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue()));

        return bases.stream()
                .map(base -> new Promotion(
                        base.id(),
                        tenantId,
                        brandId,
                        // Promotion.code (the human-readable coupon word) is a display
                        // concern the engine itself does not read — matching is by id,
                        // through presentedCouponPromotionIds — so it is left blank
                        // rather than joining coupon_codes into a query the engine's
                        // own tests never exercise with one.
                        "",
                        Promotion.Scope.valueOf(base.scope()),
                        base.stackingGroup(),
                        base.exclusive(),
                        base.priority(),
                        base.requiresCoupon(),
                        base.maximumDiscountMinor(),
                        base.currency(),
                        base.validFrom(),
                        base.validUntil(),
                        base.definitionVersion(),
                        conditionsByPromotion.getOrDefault(base.id(), List.of()),
                        actionsByPromotion.getOrDefault(base.id(), List.of())))
                .toList();
    }

    // --------------------------------------------------------- eligibility

    /** What a presented code needs checked against, read fresh — never cached across calls. */
    public Optional<CouponEligibilityRow> findCouponByCode(UUID tenantId, UUID brandId, String normalizedCode) {
        return jdbc.sql("""
                SELECT id, promotion_id, status, maximum_redemptions, maximum_per_customer, consumed_count,
                       valid_from, valid_until
                FROM pricing.coupon_codes
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND normalized_code_hash = :hash
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("hash", hash(normalizedCode))
                .query(this::mapEligibilityRow)
                .optional();
    }

    /** The same row {@link #findCouponByCode} returns, looked up by id rather than by code. */
    public Optional<CouponEligibilityRow> findCouponById(UUID tenantId, UUID couponCodeId) {
        return jdbc.sql("""
                SELECT id, promotion_id, status, maximum_redemptions, maximum_per_customer, consumed_count,
                       valid_from, valid_until
                FROM pricing.coupon_codes
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", couponCodeId)
                .query(this::mapEligibilityRow)
                .optional();
    }

    private CouponEligibilityRow mapEligibilityRow(ResultSet row, int number) throws SQLException {
        return new CouponEligibilityRow(
                row.getObject("id", UUID.class),
                row.getObject("promotion_id", UUID.class),
                row.getString("status"),
                row.getObject("maximum_redemptions", Integer.class),
                row.getInt("maximum_per_customer"),
                row.getInt("consumed_count"),
                row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                row.getObject("valid_until", OffsetDateTime.class) == null
                        ? null
                        : row.getObject("valid_until", OffsetDateTime.class).toInstant());
    }

    /** Read-only: how many times this customer has already redeemed this coupon. Zero when no usage row exists yet. */
    public int customerUsage(UUID tenantId, UUID couponCodeId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT consumed_count FROM pricing.coupon_customer_usage
                WHERE tenant_id = :tenantId AND coupon_id = :couponId AND customer_account_id = :customerId
                """)
                .param("tenantId", tenantId)
                .param("couponId", couponCodeId)
                .param("customerId", customerAccountId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    // ---------------------------------------------------------- redemption

    /**
     * The only place {@code consumed_count} moves on {@code coupon_codes}. A
     * conditional {@code UPDATE}, not a read-then-write: the row lock it takes
     * is what serializes every other concurrent attempt against the same
     * coupon.
     *
     * @return true when the increment applied — the coupon was, at this exact
     *         instant, active, in its window, and under its total limit
     */
    public boolean incrementIfWithinLimit(UUID tenantId, UUID couponCodeId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.coupon_codes
                SET consumed_count = consumed_count + 1, updated_at = :now
                WHERE id = :id AND tenant_id = :tenantId AND status = 'ACTIVE'
                  AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)
                  AND (maximum_redemptions IS NULL OR consumed_count < maximum_redemptions)
                """)
                        .param("id", couponCodeId)
                        .param("tenantId", tenantId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Compensates {@link #incrementIfWithinLimit} when the per-customer check fails it instead, or on release. */
    public void decrementCoupon(UUID tenantId, UUID couponCodeId) {
        jdbc.sql("""
                UPDATE pricing.coupon_codes SET consumed_count = consumed_count - 1
                WHERE id = :id AND tenant_id = :tenantId
                """).param("id", couponCodeId).param("tenantId", tenantId).update();
    }

    /**
     * Atomically claims one of this customer's redemption slots on this coupon.
     *
     * <p>An upsert rather than a select-then-insert: the first redemption by a
     * customer creates the row at count 1; every later one increments it,
     * guarded by the same row so two concurrent attempts from one customer
     * cannot both succeed past the cap.
     *
     * @return true when a slot was claimed
     */
    public boolean claimCustomerSlot(UUID tenantId, UUID couponCodeId, UUID customerAccountId, int maximumPerCustomer) {
        return jdbc.sql("""
                INSERT INTO pricing.coupon_customer_usage (coupon_id, tenant_id, customer_account_id, consumed_count, maximum_per_customer)
                VALUES (:couponId, :tenantId, :customerId, 1, :maxPerCustomer)
                ON CONFLICT (coupon_id, customer_account_id) DO UPDATE
                SET consumed_count = pricing.coupon_customer_usage.consumed_count + 1
                WHERE pricing.coupon_customer_usage.consumed_count < pricing.coupon_customer_usage.maximum_per_customer
                """)
                        .param("couponId", couponCodeId)
                        .param("tenantId", tenantId)
                        .param("customerId", customerAccountId)
                        .param("maxPerCustomer", maximumPerCustomer)
                        .update()
                == 1;
    }

    /** Compensates {@link #claimCustomerSlot}. */
    public void releaseCustomerSlot(UUID tenantId, UUID couponCodeId, UUID customerAccountId) {
        jdbc.sql("""
                UPDATE pricing.coupon_customer_usage SET consumed_count = consumed_count - 1
                WHERE tenant_id = :tenantId AND coupon_id = :couponId AND customer_account_id = :customerId
                """)
                .param("tenantId", tenantId)
                .param("couponId", couponCodeId)
                .param("customerId", customerAccountId)
                .update();
    }

    public void insertRedemption(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID couponCodeId,
            UUID promotionId,
            UUID quoteId,
            UUID orderId,
            @Nullable UUID customerAccountId,
            long amountMinor,
            String currency,
            Instant now) {
        jdbc.sql("""
                INSERT INTO pricing.coupon_redemptions (
                    id, tenant_id, brand_id, coupon_id, promotion_id, customer_account_id, quote_id, order_id,
                    status, amount_minor, currency, reserved_at, redeemed_at)
                VALUES (:id, :tenantId, :brandId, :couponId, :promotionId, :customerId, :quoteId, :orderId,
                    'REDEEMED', :amount, :currency, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("couponId", couponCodeId)
                .param("promotionId", promotionId)
                .param("customerId", customerAccountId)
                .param("quoteId", quoteId)
                .param("orderId", orderId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("now", utc(now))
                .update();
    }

    /**
     * Which coupon-gated promotion actually produced an adjustment on this
     * quote, its total discounted amount, and the quote's currency — all read
     * from evidence, never from a value a request supplies.
     */
    public Optional<AppliedCoupon> findCouponAppliedToQuote(UUID tenantId, UUID quoteId) {
        return jdbc.sql("""
                SELECT cc.id AS coupon_id, cc.promotion_id, q.currency,
                       COALESCE(SUM(-qa.amount_minor), 0) AS discount_minor
                FROM pricing.quote_adjustments qa
                JOIN pricing.coupon_codes cc ON cc.promotion_id = qa.source_id AND cc.tenant_id = qa.tenant_id
                JOIN pricing.quotes q ON q.id = qa.quote_id AND q.tenant_id = qa.tenant_id
                WHERE qa.tenant_id = :tenantId AND qa.quote_id = :quoteId AND qa.source_type = 'PROMOTION'
                GROUP BY cc.id, cc.promotion_id, q.currency
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query((row, n) -> new AppliedCoupon(
                        row.getObject("coupon_id", UUID.class),
                        row.getObject("promotion_id", UUID.class),
                        row.getString("currency"),
                        row.getLong("discount_minor")))
                .optional();
    }

    /** @return the released redemption's coupon and customer, or empty when nothing was reserved for this quote */
    public Optional<ReleasedRedemption> releaseRedemptionByQuote(UUID tenantId, UUID quoteId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.coupon_redemptions
                SET status = 'RELEASED', released_at = :now
                WHERE tenant_id = :tenantId AND quote_id = :quoteId AND status = 'REDEEMED'
                RETURNING coupon_id, customer_account_id
                """)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .param("now", utc(now))
                .query((row, n) -> new ReleasedRedemption(
                        row.getObject("coupon_id", UUID.class), row.getObject("customer_account_id", UUID.class)))
                .optional();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // -------------------------------------------------------------- rows

    /**
     * @param plaintextCode present only immediately after {@code draft}, absent on every later read
     * @param codeHint      the last four characters — always present, the only trace of the code on a later read
     */
    public record PromoCodeAuthoringRow(
            UUID couponId,
            UUID promotionId,
            String name,
            @Nullable String plaintextCode,
            String codeHint,
            String actionType,
            long value,
            long minBasketMinor,
            @Nullable Long maximumDiscountMinor,
            String currency,
            List<String> channels,
            List<UUID> locationIds,
            @Nullable Integer totalLimit,
            int perCustomerLimit,
            int redeemedCount,
            String status,
            int version,
            Instant validFrom,
            @Nullable Instant validUntil) {}

    public record CouponEligibilityRow(
            UUID couponId,
            UUID promotionId,
            String status,
            @Nullable Integer maximumRedemptions,
            int maximumPerCustomer,
            int consumedCount,
            Instant validFrom,
            @Nullable Instant validUntil) {

        public boolean isWithinWindow(Instant now) {
            return !now.isBefore(validFrom) && (validUntil == null || now.isBefore(validUntil));
        }

        public boolean hasCapacity() {
            return maximumRedemptions == null || consumedCount < maximumRedemptions;
        }
    }

    public record AppliedCoupon(UUID couponId, UUID promotionId, String currency, long discountMinor) {}

    public record ReleasedRedemption(
            UUID couponId, @Nullable UUID customerAccountId) {}

    private record PromotionBase(
            UUID id,
            String scope,
            String stackingGroup,
            boolean exclusive,
            int priority,
            boolean requiresCoupon,
            @Nullable Long maximumDiscountMinor,
            String currency,
            Instant validFrom,
            @Nullable Instant validUntil,
            int definitionVersion) {}
}
