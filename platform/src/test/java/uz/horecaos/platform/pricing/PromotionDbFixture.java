package uz.horecaos.platform.pricing;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.pricing.api.AudienceMembershipPort;
import uz.horecaos.platform.pricing.api.CustomerOrderHistoryPort;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromotionAuthoringService;
import uz.horecaos.platform.pricing.application.PromotionRedemptionService;
import uz.horecaos.platform.pricing.application.PromotionSimulationService;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ActionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ConditionDefinition;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcPromotionReferences;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * A tenant with a brand, a Tashkent location, a small pizza menu with a category
 * tree, a price book and a VAT profile, and the pricing services wired over a real
 * database (ADR 0140).
 *
 * <p>Margherita is 45 000 som and Cola 12 000. Margherita sits in the category
 * {@code Classic}, which sits under {@code Pizza}, so a promotion on Pizza only
 * matches it if category ancestors are resolved -- the case the previous membership
 * lookup could not express and the quote path never even asked for. There are two
 * channels (a WEB storefront and an IOS app) and two customer accounts. Time is a
 * {@link MutableClock}, so every window is tested against an instant the test chose.
 */
public final class PromotionDbFixture {

    public static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    public static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-7000-8000-0000000000a2");
    public static final UUID BRAND = UUID.fromString("00000000-0000-7000-8000-0000000000b1");
    public static final UUID OTHER_BRAND = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    public static final UUID OTHER_TENANT_BRAND = UUID.fromString("00000000-0000-7000-8000-0000000000b3");
    public static final UUID LOCATION = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    public static final UUID CUSTOMER = UUID.fromString("00000000-0000-7000-8000-0000000000d1");
    public static final UUID OTHER_CUSTOMER = UUID.fromString("00000000-0000-7000-8000-0000000000d2");
    /** 12:30 in Tashkent (UTC+5) on 2026-10-01. */
    public static final Instant TASHKENT_LUNCH = Instant.parse("2026-10-01T07:30:00Z");

    public final DataSource dataSource;
    public final JdbcClient jdbc;
    public final MutableClock clock;
    public final AtomicInteger brandOrders = new AtomicInteger();
    public final AtomicInteger channelOrders = new AtomicInteger();
    public final Map<UUID, Set<String>> audiences = new java.util.concurrent.ConcurrentHashMap<>();
    public final List<AuditFact> audits = new ArrayList<>();
    public final List<Object> published = new ArrayList<>();
    public final AtomicReference<String> actor = new AtomicReference<>("marketer");

    public final JdbcPricingStore pricingStore;
    public final JdbcPromoCodeStore promoCodeStore;
    public final JdbcPromotionStore promotionStore;
    public final QuoteService quotes;
    public final PromotionRedemptionService ledger;
    public final PromotionSimulationService simulation;
    public final PromotionAuthoringService authoring;
    public final uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService approvals;

    public UUID webChannel;
    public UUID appChannel;
    public UUID pizzaCategory;
    public UUID classicCategory;
    public UUID drinksCategory;
    public UUID margheritaProduct;
    public UUID margheritaVariant;
    public UUID colaProduct;
    public UUID colaVariant;

    public PromotionDbFixture(TestDatabase.Handle db, Instant now) {
        this(db, now, Map.of());
    }

    public PromotionDbFixture(TestDatabase.Handle db, Instant now, Map<String, Object> configurationOverrides) {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();
        clock = new MutableClock(now);
        var mapper = JsonMapper.builder().build();
        pricingStore = new JdbcPricingStore(jdbc, mapper);
        promoCodeStore = new JdbcPromoCodeStore(jdbc, mapper);
        promotionStore = new JdbcPromotionStore(jdbc, mapper);
        var channelStore = new JdbcSalesChannelStore(jdbc);
        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, mapper),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        CustomerOrderHistoryPort history = (tenantId, brandId, accountId, basis, channelId, placedBefore, excluding) ->
                basis == CustomerOrderHistoryPort.Basis.BRAND ? brandOrders.get() : channelOrders.get();
        AudienceMembershipPort audiencePort = new AudienceMembershipPort() {
            @Override
            public Set<String> segmentsOf(UUID tenantId, UUID brandId, UUID customerAccountId) {
                return audiences.getOrDefault(customerAccountId, Set.of());
            }

            @Override
            public Set<String> knownAudiences(UUID tenantId, UUID brandId, java.util.Collection<String> ids) {
                Set<String> known = new java.util.HashSet<>();
                audiences.values().forEach(known::addAll);
                known.retainAll(ids);
                return known;
            }
        };

        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("audienceMembershipPort", audiencePort);
        var references =
                new JdbcPromotionReferences(jdbc, pricingStore, beans.getBeanProvider(AudienceMembershipPort.class));
        var configuration = new FakeConfigurationResolver(configurationOverrides);
        quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channelStore,
                deliveryFees,
                PromotionTestSupport.resolver(jdbc, promoCodeStore, history, audiencePort),
                clock,
                configuration);
        ledger = new PromotionRedemptionService(promotionStore);
        simulation = new PromotionSimulationService(quotes, configuration, references, clock);

        approvals = new uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService(
                jdbc, audits::add, clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mapper);
        authoring = new PromotionAuthoringService(
                promotionStore,
                references,
                configuration,
                approvals,
                audits::add,
                published::add,
                () -> new AuthenticatedActor(Objects.requireNonNull(actor.get()), Set.of(), Map.of()),
                clock);

        seedTenancyAndCatalog();
        seedPricing();
    }

    /**
     * Runs work the way the Spring proxy does in production. Spending an approval refuses to
     * run outside a transaction (it must commit with the action it authorised or roll back with
     * it), and these services are built by hand, so a test that spends one wraps the call here.
     */
    public <T> T inTransaction(java.util.function.Supplier<T> work) {
        var template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        return Objects.requireNonNull(template.execute(status -> work.get()));
    }

    public UUID classicCategoryId() {
        return classicCategory;
    }

    public UUID colaVariantId() {
        return colaVariant;
    }

    public UUID margheritaVariantId() {
        return margheritaVariant;
    }

    /** The cart a customer prices: lines by variant. */
    public QuoteRequest cart(Map<UUID, Integer> quantities) {
        List<QuoteRequest.Line> lines = new ArrayList<>();
        int index = 0;
        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            lines.add(new QuoteRequest.Line("line-" + index++, entry.getKey(), entry.getValue(), List.of()));
        }
        return new QuoteRequest(TENANT, BRAND, LOCATION, null, "STOREFRONT", lines, null);
    }

    public QuoteRequest cartFor(
            @Nullable UUID account, String channel, Map<UUID, Integer> quantities, QuoteRequest.@Nullable Frame frame) {
        List<QuoteRequest.Line> lines = new ArrayList<>();
        int index = 0;
        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            lines.add(new QuoteRequest.Line("line-" + index++, entry.getKey(), entry.getValue(), List.of()));
        }
        return new QuoteRequest(TENANT, BRAND, LOCATION, account, channel, lines, null, null, null, null, frame);
    }

    // ----------------------------------------------------------- promotions

    public static ConditionDefinition condition(int sequence, Promotion.Condition.Type type, Object... pairs) {
        return new ConditionDefinition(sequence, type, map(pairs));
    }

    public static ActionDefinition action(int sequence, Promotion.Action.Type type, Object... pairs) {
        return new ActionDefinition(sequence, type, map(pairs));
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    /** An automatic order-scope promotion definition with sensible defaults. */
    public static PromotionDefinition definition(
            String code,
            Promotion.Scope scope,
            String group,
            List<ConditionDefinition> conditions,
            List<ActionDefinition> actions) {
        return new PromotionDefinition(
                code,
                "Promotion " + code,
                Promotion.Kind.DISCOUNT,
                scope,
                group,
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                conditions,
                actions);
    }

    /** Authors, validates and activates a definition through the real service, returning the active row. */
    public JdbcPromotionStore.PromotionRow activate(PromotionDefinition definition) {
        var drafted = authoring.create(TENANT, BRAND, definition);
        var validated = authoring.validate(TENANT, BRAND, drafted.id(), drafted.version());
        if (!validated.report().isValid()) {
            throw new AssertionError(
                    "fixture promotion does not validate: " + validated.report().refusals());
        }
        var result = authoring.activate(
                TENANT, BRAND, drafted.id(), validated.promotion().version(), "fixture");
        if (result.isPending()) {
            throw new AssertionError("fixture promotion needs a second person; lower it below the threshold");
        }
        return Objects.requireNonNull(result.promotion());
    }

    // ------------------------------------------------------------------ seed

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE pricing.coupon_redemptions, pricing.coupon_customer_usage, pricing.coupon_codes, "
                        + "pricing.promotion_redemptions, pricing.promotion_customer_usage, "
                        + "pricing.promotion_definition_versions, "
                        + "pricing.promotion_actions, pricing.promotion_conditions, pricing.promotions, "
                        + "pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.translations, catalog.catalog_products, "
                        + "catalog.category_products, catalog.categories, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        // The cascade above empties audit.approval_policies too, platform rows included, because
        // the policy table references tenants. Production never loses the V0461 policy, so a test
        // that wants to prove the four-eyes gate puts back the very statement the migration ran,
        // not a hand-written look-alike.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(new String(
                    new org.springframework.core.io.ClassPathResource(
                                    "db/migration/V0461__promotion_activation_is_governed_by_a_policy.sql")
                            .getInputStream()
                            .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | java.sql.SQLException e) {
            throw new IllegalStateException("could not restore the V0461 platform approval policy", e);
        }
    }

    private void seedTenancyAndCatalog() {
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "promo-" + tenant.toString().substring(30))
                    .update();
        }
        brand(TENANT, BRAND, "MAIN");
        brand(TENANT, OTHER_BRAND, "SECOND");
        brand(OTHER_TENANT, OTHER_TENANT_BRAND, "FOREIGN");
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        for (UUID account : List.of(CUSTOMER, OTHER_CUSTOMER)) {
            jdbc.sql("""
                    INSERT INTO customer.customer_accounts (id, tenant_id, status, version)
                    VALUES (:id, :tenantId, 'ACTIVE', 1)
                    """).param("id", account).param("tenantId", TENANT).update();
        }
        webChannel = UUID.randomUUID();
        appChannel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'STOREFRONT', 'ACTIVE')
                """).param("id", webChannel).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'APP', 'IOS', 'APP', 'ACTIVE')
                """).param("id", appChannel).param("tenantId", TENANT).update();
        for (String method : List.of("CLICK", "CASH")) {
            jdbc.sql("""
                    INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                    VALUES (:id, :tenantId, :code, :code, 'OPERATOR', 'ACTIVE')
                    ON CONFLICT ON CONSTRAINT uq_payment_method_code DO NOTHING
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("code", method)
                    .update();
            jdbc.sql("""
                    INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                    VALUES (:tenantId, :channelId, :method, true)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("tenantId", TENANT)
                    .param("channelId", webChannel)
                    .param("method", method)
                    .update();
        }

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        pizzaCategory = category(catalogId, null, "PIZZA");
        classicCategory = category(catalogId, pizzaCategory, "CLASSIC");
        drinksCategory = category(catalogId, null, "DRINKS");

        margheritaProduct = product(catalogId, "MARGHERITA", "Margherita", classicCategory);
        margheritaVariant = variant(margheritaProduct, "SKU-MARGHERITA");
        colaProduct = product(catalogId, "COLA", "Cola", drinksCategory);
        colaVariant = variant(colaProduct, "SKU-COLA");

        for (String channel : List.of("STOREFRONT", "APP")) {
            jdbc.sql("""
                    INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                        status, content_hash, activated_at)
                    VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("catalogId", catalogId)
                    .param("channel", channel)
                    .update();
        }
    }

    private void brand(UUID tenant, UUID brand, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brand)
                .param("tenantId", tenant)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    private UUID category(UUID catalogId, @Nullable UUID parent, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.categories (id, tenant_id, brand_id, catalog_id, parent_category_id, code, status)
                VALUES (:id, :tenantId, :brandId, :catalogId, :parent, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("parent", parent)
                .param("code", code)
                .update();
        return id;
    }

    private UUID product(UUID catalogId, String code, String name, UUID category) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                VALUES (:tenantId, :brandId, :catalogId, :productId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("productId", id)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.category_products (tenant_id, brand_id, category_id, product_id)
                VALUES (:tenantId, :brandId, :categoryId, :productId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("categoryId", category)
                .param("productId", id)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', :name)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", id)
                .param("name", name)
                .update();
        return id;
    }

    private UUID variant(UUID productId, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .param("sku", sku)
                .update();
        return id;
    }

    private void seedPricing() {
        UUID priceBookId = UUID.randomUUID();
        var from = OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(30)), ZoneOffset.UTC);
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
        price(priceBookId, margheritaVariant, 45_000L, from);
        price(priceBookId, colaVariant, 12_000L, from);
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

    private void price(UUID priceBookId, UUID variantId, long amountMinor, OffsetDateTime from) {
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBookId)
                .param("variantId", variantId)
                .param("amount", amountMinor)
                .param("from", from)
                .update();
    }

    /** Lets a test move time without sleeping. */
    public static final class MutableClock extends java.time.Clock {
        private Instant now;

        public MutableClock(Instant now) {
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

        public void set(Instant instant) {
            now = instant;
        }

        public void advanceBy(Duration duration) {
            now = now.plus(duration);
        }
    }
}
