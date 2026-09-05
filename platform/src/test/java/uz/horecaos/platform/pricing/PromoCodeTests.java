package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.pricing.api.PromoCodeQueryPort;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.PromoCodeDraft;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.application.PromoCodeRedemptionService;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.PromoCodeAuthoringRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0072: a promo code discounts a quote, changes its context hash, and is
 * redeemed at most once even when two checkouts race for the last use of a
 * single-use code.
 *
 * <p>Runs against V0093's already-built {@code pricing.promotions} /
 * {@code coupon_codes} / {@code coupon_customer_usage} /
 * {@code coupon_redemptions} schema — this suite is that schema's first
 * exerciser, and against a real database for the same reason
 * {@code QuoteAndReservationTests} runs against one: whether a coupon's limit
 * holds under concurrency only shows up there.
 */
class PromoCodeTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private QuoteService quotes;
    private PromoCodeAuthoringService authoring;
    private PromoCodeEligibilityService eligibility;
    private PromoCodeRedemptionService redemptions;
    private MutableClock clock;
    private UUID burgerVariant;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for promo code tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE pricing.coupon_redemptions, pricing.coupon_customer_usage, pricing.coupon_codes, "
                        + "pricing.promotion_actions, pricing.promotion_conditions, pricing.promotions, "
                        + "pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.translations, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        var pricingStore = new JdbcPricingStore(jdbc, JsonMapper.builder().build());
        var promoCodeStore = new JdbcPromoCodeStore(jdbc, JsonMapper.builder().build());
        var channelStore = new JdbcSalesChannelStore(jdbc);

        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, JsonMapper.builder().build()),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        eligibility = new PromoCodeEligibilityService(promoCodeStore);
        redemptions = new PromoCodeRedemptionService(promoCodeStore, clock);
        authoring = new PromoCodeAuthoringService(promoCodeStore, clock);
        quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channelStore,
                deliveryFees,
                promoCodeStore,
                eligibility,
                clock);

        seedTenancyAndCatalog();
        seedPricing();
    }

    // -------------------------------------------------------------- authoring

    @Test
    @DisplayName("drafting, activating and retiring a promo code moves the promotion and the coupon together")
    void draftActivateRetireMoveBothRowsTogether() {
        PromoCodeAuthoringRow drafted = authoring.draft(TENANT, BRAND, percentageDraft("WELCOME10", 1_000, null));
        assertThat(drafted.status())
                .as("pricing.coupon_codes has no DRAFT state; SUSPENDED is what keeps it unredeemable")
                .isEqualTo("SUSPENDED");
        assertThat(promotionStatus(drafted.promotionId())).isEqualTo("DRAFT");
        assertThat(drafted.plaintextCode())
                .as("the plaintext is returned exactly once, in the draft response")
                .isEqualTo("WELCOME10");

        // A SUSPENDED coupon behind a DRAFT promotion is not yet in the
        // pricing engine's active list at all.
        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1))).total().minor()).isEqualTo(50_000L);

        authoring.activate(TENANT, BRAND, drafted.couponId());
        assertThat(couponStatus(drafted.couponId())).isEqualTo("ACTIVE");
        assertThat(promotionStatus(drafted.promotionId())).isEqualTo("ACTIVE");

        authoring.retire(TENANT, BRAND, drafted.couponId());
        assertThat(couponStatus(drafted.couponId())).isEqualTo("ARCHIVED");
        assertThat(promotionStatus(drafted.promotionId())).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("a later read shows only the code hint, never the plaintext")
    void laterReadsShowOnlyTheHint() {
        PromoCodeAuthoringRow drafted = authoring.draft(TENANT, BRAND, percentageDraft("HINTTEST99", 1_000, null));
        assertThat(drafted.codeHint()).isEqualTo("ST99");

        PromoCodeAuthoringRow reread = authoring.list(TENANT, BRAND).stream()
                .filter(row -> row.couponId().equals(drafted.couponId()))
                .findFirst()
                .orElseThrow();
        assertThat(reread.plaintextCode())
                .as("the plaintext is never returned again — only the hash and the hint are stored")
                .isNull();
        assertThat(reread.codeHint()).isEqualTo("ST99");
    }

    @Test
    @DisplayName("more than one promo code may be live for a brand at once")
    void multipleCodesCanBeLiveTogether() {
        var first = activate(percentageDraft("A10", 1_000, null));
        var second = activate(fixedDraft("B5000", 5_000L, null));

        assertThat(couponStatus(first.couponId())).isEqualTo("ACTIVE");
        assertThat(couponStatus(second.couponId())).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------- pricing

    @Test
    @DisplayName("a presented, eligible code discounts the order and changes the context hash")
    void anEligibleCodeDiscountsTheOrderAndEntersTheHash() {
        var promo = activate(percentageDraft("SAVE10", 1_000, null));

        var withoutCode = quotes.quote(cart(Map.of(burgerVariant, 1)));
        var withCode = quotes.quote(cartWithCode(Map.of(burgerVariant, 1), "SAVE10", "k-with-code"));

        assertThat(withoutCode.total().minor()).isEqualTo(50_000L);
        // 10% off 50,000.
        assertThat(withCode.total().minor()).isEqualTo(45_000L);
        assertThat(withCode.discount().minor()).isEqualTo(5_000L);
        assertThat(withCode.contextHash())
                .as("presenting a code is an input to the hash, exactly like a changed price book")
                .isNotEqualTo(withoutCode.contextHash());

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pricing.quote_adjustments
                        WHERE tenant_id = :tenantId AND quote_id = :quoteId
                          AND source_type = 'PROMOTION' AND source_id = :promotionId
                        """)
                        .param("tenantId", TENANT)
                        .param("quoteId", withCode.quoteId())
                        .param("promotionId", promo.promotionId())
                        .query(Long.class)
                        .single())
                .as("the applied promotion is recorded as evidence on the quote, which is how "
                        + "PromoCodeRedemptionService later finds it without trusting a request field")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("a SUSPENDED code is silently excluded from the quote rather than refusing the cart")
    void aSuspendedCodeIsExcludedNotRefused() {
        authoring.draft(TENANT, BRAND, percentageDraft("PENDING10", 1_000, null));

        var quote = quotes.quote(cartWithCode(Map.of(burgerVariant, 1), "PENDING10", "k-draft"));
        assertThat(quote.total().minor())
                .as("an ineligible code produces a quote with no discount, never a refusal of the whole cart")
                .isEqualTo(50_000L);
    }

    @Test
    @DisplayName("an unknown code is silently excluded from the quote")
    void anUnknownCodeIsExcluded() {
        var quote = quotes.quote(cartWithCode(Map.of(burgerVariant, 1), "NOSUCHCODE", "k-unknown"));
        assertThat(quote.total().minor()).isEqualTo(50_000L);
    }

    // --------------------------------------------------------- eligibility

    @Test
    @DisplayName("eligibility is refused for each reason ADR 0072 names")
    void eligibilityReasons() {
        assertThat(eligibility.check(TENANT, BRAND, "GHOST", null, NOW).reason())
                .isEqualTo(PromoCodeQueryPort.Eligibility.Reason.CODE_NOT_FOUND);

        authoring.draft(TENANT, BRAND, percentageDraft("DRAFT1", 500, null));
        assertThat(eligibility.check(TENANT, BRAND, "DRAFT1", null, NOW).reason())
                .isEqualTo(PromoCodeQueryPort.Eligibility.Reason.CODE_NOT_ACTIVE);

        activate(percentageDraft("FUTURE1", 500, null, NOW.plus(Duration.ofDays(1)), null));
        assertThat(eligibility.check(TENANT, BRAND, "FUTURE1", null, NOW).reason())
                .isEqualTo(PromoCodeQueryPort.Eligibility.Reason.CODE_NOT_YET_ACTIVE);

        activate(percentageDraft("GONE1", 500, null, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1))));
        assertThat(eligibility.check(TENANT, BRAND, "GONE1", null, NOW).reason())
                .isEqualTo(PromoCodeQueryPort.Eligibility.Reason.CODE_EXPIRED);

        var oneUse = activate(percentageDraftWithLimits("ONEUSE", 500, 1, 100));
        assertThat(jdbc.sql("UPDATE pricing.coupon_codes SET consumed_count = 1 WHERE id = :id")
                        .param("id", oneUse.couponId())
                        .update())
                .isEqualTo(1);
        assertThat(eligibility.check(TENANT, BRAND, "ONEUSE", null, NOW).reason())
                .isEqualTo(PromoCodeQueryPort.Eligibility.Reason.REDEMPTION_LIMIT_REACHED);

        var perCustomer = activate(percentageDraftWithLimits("PERCUST", 500, null, 1));
        assertThat(eligibility.check(TENANT, BRAND, "PERCUST", null, NOW).isEligible())
                .as("a guest cart's per-customer cap simply does not apply — there is no identity to count against")
                .isTrue();
        assertThat(eligibility.check(TENANT, BRAND, "PERCUST", CUSTOMER, NOW).isEligible())
                .isTrue();
        jdbc.sql("""
                        INSERT INTO pricing.coupon_customer_usage (coupon_id, tenant_id, customer_account_id, consumed_count, maximum_per_customer)
                        VALUES (:couponId, :tenantId, :customerId, 1, 1)
                        """)
                .param("couponId", perCustomer.couponId())
                .param("tenantId", TENANT)
                .param("customerId", CUSTOMER)
                .update();
        assertThat(eligibility.check(TENANT, BRAND, "PERCUST", CUSTOMER, NOW).reason())
                .isEqualTo(PromoCodeQueryPort.Eligibility.Reason.PER_CUSTOMER_LIMIT_REACHED);
    }

    // ------------------------------------------------------------ redemption

    @Test
    @DisplayName("two concurrent checkouts cannot both consume the last redemption of a single-use code")
    void twoConcurrentCheckoutsCannotBothConsumeTheLastRedemption() throws Exception {
        var code = activate(percentageDraftWithLimits("LASTONE", 1_000, 1, 100));

        Quote quoteOne = quotes.quote(cartWithCode(Map.of(burgerVariant, 1), "LASTONE", "k-race-1"));
        Quote quoteTwo = quotes.quote(cartWithCode(Map.of(burgerVariant, 1), "LASTONE", "k-race-2"));

        // Both quotes legitimately show the discount: the coupon still had
        // capacity when each was priced. This is exactly the race ADR 0072
        // describes — the atomic write at redemption is what has to settle it.
        assertThat(quoteOne.discount().minor()).isEqualTo(5_000L);
        assertThat(quoteTwo.discount().minor()).isEqualTo(5_000L);

        List<PromoCodeRedemptionPort.RedemptionResult> results = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (var quote : List.of(quoteOne, quoteTwo)) {
                UUID quoteId = quote.quoteId();
                attempts.add(pool.submit(() -> results.add(
                        redemptions.reserveForQuote(TENANT, BRAND, quoteId, UUID.randomUUID(), CUSTOMER, NOW))));
            }
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long redeemed = results.stream()
                .filter(r -> r.result() == PromoCodeRedemptionPort.RedemptionResult.Result.REDEEMED)
                .count();
        long refused = results.stream()
                .filter(PromoCodeRedemptionPort.RedemptionResult::isRefused)
                .count();

        assertThat(redeemed)
                .as("exactly one of two concurrent checkouts may redeem the last use")
                .isEqualTo(1);
        assertThat(refused).isEqualTo(1);

        assertThat(jdbc.sql("SELECT consumed_count FROM pricing.coupon_codes WHERE id = :id")
                        .param("id", code.couponId())
                        .query(Integer.class)
                        .single())
                .as("the counter reflects exactly the one redemption that actually happened, never two, "
                        + "never a phantom increment left behind by the loser")
                .isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM pricing.coupon_redemptions WHERE coupon_id = :id AND status = 'REDEEMED'")
                        .param("id", code.couponId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("releasing a redemption gives the slot back")
    void releasingARedemptionGivesTheSlotBack() {
        var code = activate(percentageDraftWithLimits("RELEASEME", 1_000, 1, 100));
        Quote quote = quotes.quote(cartWithCode(Map.of(burgerVariant, 1), "RELEASEME", "k-release"));

        var first = redemptions.reserveForQuote(TENANT, BRAND, quote.quoteId(), UUID.randomUUID(), CUSTOMER, NOW);
        assertThat(first.result()).isEqualTo(PromoCodeRedemptionPort.RedemptionResult.Result.REDEEMED);

        assertThat(redemptions.release(TENANT, quote.quoteId())).isTrue();
        assertThat(jdbc.sql("SELECT consumed_count FROM pricing.coupon_codes WHERE id = :id")
                        .param("id", code.couponId())
                        .query(Integer.class)
                        .single())
                .isZero();

        // The slot is genuinely back: a fresh quote against the same code is
        // eligible again, and a second reservation for a different quote succeeds.
        Quote secondQuote = quotes.quote(cartWithCode(Map.of(burgerVariant, 1), "RELEASEME", "k-release-2"));
        assertThat(redemptions
                        .reserveForQuote(TENANT, BRAND, secondQuote.quoteId(), UUID.randomUUID(), CUSTOMER, NOW)
                        .result())
                .isEqualTo(PromoCodeRedemptionPort.RedemptionResult.Result.REDEEMED);
    }

    @Test
    @DisplayName("a quote with no applied coupon has nothing to reserve")
    void aQuoteWithNoCouponHasNothingToReserve() {
        Quote quote = quotes.quote(cart(Map.of(burgerVariant, 1)));
        assertThat(redemptions
                        .reserveForQuote(TENANT, BRAND, quote.quoteId(), UUID.randomUUID(), CUSTOMER, NOW)
                        .result())
                .isEqualTo(PromoCodeRedemptionPort.RedemptionResult.Result.NO_CODE_APPLIED);
    }

    @Test
    @DisplayName("authoring rejects a discount shape outside the closed set's own value rules")
    void authoringValidatesTheClosedShapeSet() {
        assertThatThrownBy(() -> authoring.draft(TENANT, BRAND, percentageDraft("BAD1", 0, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authoring.draft(TENANT, BRAND, percentageDraft("BAD2", 10_001, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authoring.draft(TENANT, BRAND, fixedDraft("BAD3", 0L, null)))
                .isInstanceOf(ApiException.class);
    }

    // -------------------------------------------------------------- helpers

    private PromoCodeAuthoringRow activate(PromoCodeDraft draft) {
        PromoCodeAuthoringRow drafted = authoring.draft(TENANT, BRAND, draft);
        authoring.activate(TENANT, BRAND, drafted.couponId());
        return drafted;
    }

    private PromoCodeDraft percentageDraft(String code, long basisPoints, @Nullable Long maxDiscountMinor) {
        return percentageDraft(code, basisPoints, maxDiscountMinor, null, null);
    }

    private PromoCodeDraft percentageDraft(
            String code,
            long basisPoints,
            @Nullable Long maxDiscountMinor,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {
        return new PromoCodeDraft(
                "Promo " + code,
                code,
                DiscountShape.PERCENTAGE_OFF_ORDER,
                basisPoints,
                maxDiscountMinor,
                "UZS",
                0,
                List.of(),
                List.of(),
                null,
                100,
                validFrom,
                validUntil);
    }

    private PromoCodeDraft percentageDraftWithLimits(
            String code, long basisPoints, @Nullable Integer totalLimit, int perCustomerLimit) {
        return new PromoCodeDraft(
                "Promo " + code,
                code,
                DiscountShape.PERCENTAGE_OFF_ORDER,
                basisPoints,
                null,
                "UZS",
                0,
                List.of(),
                List.of(),
                totalLimit,
                perCustomerLimit,
                null,
                null);
    }

    private PromoCodeDraft fixedDraft(String code, long amountMinor, @Nullable Long maxDiscountMinor) {
        return new PromoCodeDraft(
                "Promo " + code,
                code,
                DiscountShape.FIXED_AMOUNT_OFF_ORDER,
                amountMinor,
                maxDiscountMinor,
                "UZS",
                0,
                List.of(),
                List.of(),
                null,
                100,
                null,
                null);
    }

    private String promotionStatus(UUID promotionId) {
        return jdbc.sql("SELECT status FROM pricing.promotions WHERE id = :id")
                .param("id", promotionId)
                .query(String.class)
                .single();
    }

    private String couponStatus(UUID couponId) {
        return jdbc.sql("SELECT status FROM pricing.coupon_codes WHERE id = :id")
                .param("id", couponId)
                .query(String.class)
                .single();
    }

    private QuoteRequest cart(Map<UUID, Integer> quantities) {
        return cartWithCode(quantities, null, null);
    }

    private QuoteRequest cartWithCode(
            Map<UUID, Integer> quantities, @Nullable String code, @Nullable String idempotencyKey) {
        List<QuoteRequest.Line> lines = new ArrayList<>();
        int index = 0;
        for (var entry : quantities.entrySet()) {
            lines.add(new QuoteRequest.Line("line-" + index++, entry.getKey(), entry.getValue(), List.of()));
        }
        return new QuoteRequest(TENANT, BRAND, LOCATION, CUSTOMER, "STOREFRONT", lines, idempotencyKey, null, code);
    }

    private void seedTenancyAndCatalog() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'promo-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1)
                """).param("id", CUSTOMER).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'STOREFRONT', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        UUID productId = UUID.randomUUID();
        burgerVariant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, 'SKU-BURGER', 'ACTIVE')
                """)
                .param("id", burgerVariant)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                VALUES (:tenantId, :brandId, :catalogId, :productId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', 'Burger')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void seedPricing() {
        UUID priceBookId = UUID.randomUUID();
        var from = java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, 'BRAND_MENU', 'UZS', 'ACTIVE', :from, 0)
                """)
                .param("id", priceBookId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                    scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'BRAND', NULL, :from, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBookId)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, 50000, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBookId)
                .param("variantId", burgerVariant)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code, mode,
                    rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", from)
                .update();
    }

    /** Lets a test move time forward without sleeping. */
    private static final class MutableClock extends java.time.Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
