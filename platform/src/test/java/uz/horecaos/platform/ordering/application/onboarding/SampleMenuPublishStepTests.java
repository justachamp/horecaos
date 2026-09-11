package uz.horecaos.platform.ordering.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.api.SampleMenuPort;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.SampleMenuService;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.inventory.api.StockListingPort;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.application.StockListingPortAdapter;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.pricing.api.SampleMenuPricingPort;
import uz.horecaos.platform.pricing.application.PriceAuthoringService;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.application.SampleMenuPricing;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.catalog.PricingMenuPriceLookup;
import uz.horecaos.platform.pricing.infrastructure.catalog.PricingVariantLookup;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;
import uz.horecaos.platform.tenancy.application.ServiceabilityService;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingStepHandlers;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * ADR 0099: {@code SAMPLE_MENU_PUBLISH} against the migrated schema.
 *
 * <p>Wires the real {@code catalog}, {@code pricing} and {@code inventory}
 * services the production step calls — a stand-in port would prove the handler
 * calls a stand-in, and what this step exists to prove is that a tenant which
 * authored nothing can nevertheless pass the two checks that gate activation.
 * Those two checks are therefore run here, for real, as the assertion: {@link
 * #theTwoValidationStepsPassWithOnlyTheSampleMenu}.
 */
class SampleMenuPublishStepTests {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CHANNEL_CODE = "STOREFRONT";

    /** What {@code SampleMenuContent} carries, asserted from outside it. */
    private static final int SAMPLE_PRODUCTS = 10;

    private static final int SAMPLE_CATEGORIES = 4;

    /**
     * What "sample" is called in each locale the content carries. A list of
     * entries rather than a {@code Map}, so a failure names the locale in a
     * deterministic order.
     */
    private static final List<Map.Entry<String, String>> SAMPLE_PREFIXES =
            List.of(Map.entry("uz", "Namuna"), Map.entry("ru", "Образец"), Map.entry("en", "Sample"));

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private UUID tenantId;
    private UUID brandId;
    private UUID locationId;
    private UUID channelId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");
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
        jdbc.sql("TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.translations, catalog.category_products, "
                        + "catalog.catalog_products, catalog.categories, catalog.fiscal_classifications, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE inventory.stock_items CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.channel_fulfillment_modes, tenant.sales_channel_locations, "
                        + "tenant.sales_channels CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        insertTenant();
        insertBrand(brandId, "MAIN");
        insertLocation(locationId, brandId, "MAIN01");
        // Production's StorefrontChannelSeeder gives every tenant this channel
        // when the tenant is created, long before an onboarding run starts. A
        // fixture without it would be testing a shape production never produces.
        channelId = insertChannel();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("builds, prices, stocks and publishes a sample menu for a tenant that authored nothing")
    void createsPricesStocksAndPublishesTheSampleMenu() {
        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(result.result())
                .containsEntry("catalogCode", SampleMenuPort.SAMPLE_CATALOG_CODE)
                .containsEntry("categories", SAMPLE_CATEGORIES)
                .containsEntry("products", SAMPLE_PRODUCTS)
                .containsEntry("variants", SAMPLE_PRODUCTS)
                .containsEntry("locations", 1)
                .containsEntry("channel", CHANNEL_CODE)
                .containsEntry("created", true);

        // Ids and counts only (ADR 0029). A dish name is not personal data, but
        // the rule this snapshot is held to is "nothing but ids and counts", and
        // a snapshot that grew a name field would be the first step away from it.
        assertThat(result.result().keySet())
                .containsExactlyInAnyOrder(
                        "catalogId",
                        "catalogCode",
                        "publicationId",
                        "priceBookId",
                        "categories",
                        "products",
                        "variants",
                        "locations",
                        "offeringsCreated",
                        "stockItemsListed",
                        "pricesSet",
                        "channel",
                        "created");

        assertThat(countProducts()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countCategories()).isEqualTo(SAMPLE_CATEGORIES);
        assertThat(countOfferings()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countStockItems()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(publishedCatalogCode()).contains(SampleMenuPort.SAMPLE_CATALOG_CODE);

        // The prices are the tenant's own currency, never one this step picked.
        assertThat(priceCurrencies()).containsExactly("UZS");
    }

    @Test
    @DisplayName("every sample code, SKU and name is marked as a sample in all three locales")
    void marksEverythingItCreatesAsASample() {
        handler().execute(context());

        assertThat(productCodes()).allMatch(code -> code.startsWith("SAMPLE-"));
        assertThat(variantSkus()).allMatch(sku -> sku.startsWith("SAMPLE-"));

        // Three locales on every category and product, and every one of those
        // names says "sample" in its own language. That naming is the only thing
        // standing between a tenant that activated on the sample and a customer
        // ordering from it, so it is asserted rather than assumed.
        //
        // Each locale against its own prefix, not against all three: an OR over
        // the three is satisfied by Uzbek text under the "ru" key, which is
        // exactly the copy-paste SampleMenuContent's fourteen hand-written
        // Map.of literals invite — and which getOrDefault(locale, uz) would also
        // write silently for a locale key somebody dropped.
        for (Map.Entry<String, String> expected : SAMPLE_PREFIXES) {
            String locale = expected.getKey();
            assertThat(translatedNames(locale))
                    .as("names in %s", locale)
                    .hasSize(SAMPLE_PRODUCTS + SAMPLE_CATEGORIES)
                    .allMatch(name -> name.startsWith(expected.getValue()));
        }
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("a second attempt finds the sample it already made and creates nothing more")
    void isIdempotentOnRetry() {
        StepResult first = handler().execute(context());
        assertThat(first.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        UUID publicationId = UUID.fromString((String) first.result().get("publicationId"));

        StepResult second = handler().execute(context());

        assertThat(second.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(second.result()).containsEntry("created", false);
        assertThat(second.result().get("catalogId")).isEqualTo(first.result().get("catalogId"));
        assertThat(UUID.fromString((String) second.result().get("publicationId")))
                .as("a retry must not snapshot a second publication over a perfectly good one")
                .isEqualTo(publicationId);

        assertThat(countProducts()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countCategories()).isEqualTo(SAMPLE_CATEGORIES);
        assertThat(countOfferings()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countStockItems()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countCatalogs()).isEqualTo(1);
        assertThat(countPriceBooks()).isEqualTo(1);
        assertThat(countPublications()).isEqualTo(1);
        assertThat(countOpenPrices())
                .as("re-pricing an already-priced book would write a price history the tenant never made")
                .isEqualTo(SAMPLE_PRODUCTS);
    }

    /**
     * The operator's own decision survives the machine running again.
     *
     * <p>The re-assert exists so that a location added between two runs starts
     * offering the sample. It must not also mean that an operator who took a
     * sample dish off the storefront finds it back on after the next run —
     * {@code StockListingPortAdapter.ensureListed} already refuses to overwrite a
     * deliberately sold-out stock item, and an offering row is the same kind of
     * state.
     */
    @Test
    @DisplayName("an offering an operator turned off is not turned back on by a later attempt")
    void leavesAnOperatorsOwnOfferingStatusAlone() {
        assertThat(handler().execute(context()).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);

        List<UUID> variants = sampleVariantIds();
        // HIDDEN and UNAVAILABLE mean different things to the storefront — one
        // drops the dish, the other greys it out — and neither is this step's to
        // revoke. HIDDEN specifically is the one a read-then-write would get
        // wrong, because offeringsForLocation filters it out in SQL and it would
        // read as absent.
        setOfferingStatus(variants.get(0), "HIDDEN");
        setOfferingStatus(variants.get(1), "UNAVAILABLE");

        StepResult second = handler().execute(context());

        assertThat(second.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(second.result())
                .as("nothing was missing, so nothing was created")
                .containsEntry("offeringsCreated", 0);
        assertThat(offeringStatus(variants.get(0))).isEqualTo("HIDDEN");
        assertThat(offeringStatus(variants.get(1))).isEqualTo("UNAVAILABLE");
        assertThat(countAllOfferings()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countOfferings()).isEqualTo(SAMPLE_PRODUCTS - 2);
    }

    /** The other half of the same rule: a location with no offering yet does get one. */
    @Test
    @DisplayName("a location added between two runs starts offering the sample")
    void createsOfferingsForALocationAddedLater() {
        assertThat(handler().execute(context()).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);

        UUID second = UUID.randomUUID();
        insertLocation(second, brandId, "MAIN02");

        StepResult result = handler().execute(context());

        assertThat(result.result()).containsEntry("offeringsCreated", SAMPLE_PRODUCTS);
        assertThat(countAllOfferings()).isEqualTo(SAMPLE_PRODUCTS * 2);
    }

    /**
     * The half-finished attempt, which is the case idempotency actually exists
     * for: the step died after the catalog and before the publication, and the
     * one that follows has to finish the job rather than start a second one.
     */
    @Test
    @DisplayName("an attempt that died halfway is finished by the next one, not duplicated")
    void resumesAnAttemptThatDiedBeforePublishing() {
        UUID catalogId = catalogPort()
                .installSample(tenantId, brandId, List.of(locationId))
                .catalogId();
        assertThat(publishedCatalogCode()).as("nothing is published yet").isEmpty();

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(result.result()).containsEntry("created", false);
        assertThat(result.result().get("catalogId")).isEqualTo(catalogId.toString());
        assertThat(countCatalogs()).isEqualTo(1);
        assertThat(countProducts()).isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countStockItems())
                .as("a resumed attempt owes the stock rows the dead one never wrote")
                .isEqualTo(SAMPLE_PRODUCTS);
        assertThat(countOpenPrices())
                .as("and the prices, which nothing else here would notice were missing")
                .isEqualTo(SAMPLE_PRODUCTS);
        assertThat(publishedCatalogCode()).contains(SampleMenuPort.SAMPLE_CATALOG_CODE);
    }

    // ------------------------------------------------------------------ the point of the step

    /**
     * The exit criterion of ADR 0099, asserted with the real handlers: with the
     * sample menu as the only menu the tenant has, both checks that gate
     * activation pass.
     */
    @Test
    @DisplayName("CATALOG_READINESS_VALIDATE and ACTIVATION_SMOKE_TEST both pass on the sample menu alone")
    void theTwoValidationStepsPassWithOnlyTheSampleMenu() {
        bindChannelToLocation(channelId);
        enableFulfillmentMode(channelId, "PICKUP");

        assertThat(catalogReadiness().execute(context()).outcome())
                .as("before the sample menu exists, catalogue readiness must fail")
                .isEqualTo(StepResult.Outcome.FAILED);

        assertThat(handler().execute(context()).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);

        assertThat(catalogReadiness().execute(context()).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);

        StepResult smoke = smokeTest().execute(context());
        assertThat(smoke.outcome())
                .as("the smoke test's own detail when it fails: %s", smoke.detail())
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    /** The public menu endpoint's own query, on the sample the step just published. */
    @Test
    @DisplayName("the published sample menu is readable through the anonymous storefront query")
    void theSampleMenuIsReadableThroughTheStorefront() {
        assertThat(handler().execute(context()).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);

        for (Map.Entry<String, String> expected : SAMPLE_PREFIXES) {
            String locale = expected.getKey();
            Optional<StorefrontCatalogQuery.StorefrontMenu> menu =
                    storefront().menuFor(tenantId, brandId, locationId, locale, CHANNEL_CODE);

            assertThat(menu).as("menu in %s", locale).isPresent();
            List<StorefrontCatalogQuery.MenuProduct> products =
                    menu.orElseThrow().products();
            assertThat(products).hasSize(SAMPLE_PRODUCTS);
            assertThat(products).allSatisfy(product -> {
                // The locale's own prefix, not merely "not blank": a name missing
                // from the publication snapshot comes back as an arbitrary other
                // locale's text rather than as null (StorefrontCatalogQuery falls
                // back to the first entry it has), so isNotBlank cannot tell a
                // real per-locale read from a fallback and this can.
                assertThat(product.name())
                        .as("a %s customer must see a %s name, not a fallback", locale, locale)
                        .startsWith(expected.getValue());
                // description() has no fallback at all — a dropped locale key
                // shows up here as a null nothing else would notice.
                assertThat(product.description())
                        .as("a %s customer must see a %s description", locale, locale)
                        .isNotBlank();
                assertThat(product.variants()).isNotEmpty();
                // A published, offered, priced item. A null price here is the
                // shape ACTIVATION_SMOKE_TEST would refuse two steps later.
                assertThat(product.variants())
                        .allSatisfy(variant -> assertThat(variant.amountMinor())
                                .as("variant %s has no price", variant.sku())
                                .isNotNull());
            });
        }
    }

    // ------------------------------------------------------------------ refusals

    /**
     * The gap this test found: {@code CatalogPublicationService.publish} throws
     * for an unregistered channel, and {@code OnboardingService} maps a thrown
     * handler to {@code RETRY}. Without the explicit check, a tenant whose
     * storefront channel was never seeded retried a permanent condition forever
     * instead of failing where a human could see it.
     */
    @Test
    @DisplayName("a tenant with no storefront channel fails permanently rather than retrying forever")
    void failsWhenTheTenantHasNoStorefrontChannel() {
        jdbc.sql("DELETE FROM tenant.sales_channels WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .update();

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_CHANNEL");
    }

    /**
     * A channel row exists after it is archived, so {@code isEmpty()} was not
     * enough: the step wrote the whole sample and then {@code publish} threw for
     * the archived channel, which {@code OnboardingService} maps to
     * {@code RETRY/TRANSIENT_INFRASTRUCTURE} — the exact shape the pre-check
     * exists to prevent, five attempts long.
     */
    @Test
    @DisplayName("an archived storefront channel fails NO_CHANNEL before anything is written")
    void failsWhenTheStorefrontChannelIsArchived() {
        jdbc.sql("UPDATE tenant.sales_channels SET status = 'ARCHIVED' WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .update();

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_CHANNEL");
        assertThat(countCatalogs())
                .as("the pre-check's whole point is that nothing is written first")
                .isZero();
    }

    /**
     * {@code INACTIVE} is the half of {@code sellable()} only this pre-check
     * catches. {@code CatalogPublicationService} refuses {@code ARCHIVED} alone,
     * so for an archived channel the pre-check merely converts a throw into a
     * clean failure and an {@code ARCHIVED}-only mutant of the condition would
     * still pass the test above. Without the {@code sellable()} half, a tenant
     * that switched its storefront off gets the whole sample menu written and
     * published to a dead channel, passes {@code CATALOG_READINESS_VALIDATE} on
     * it — that step reads publication rows, never channel status — and only
     * fails eight steps later at {@code ACTIVATION_SMOKE_TEST}, with a catalogue
     * someone then has to un-publish.
     */
    @Test
    @DisplayName("an inactive storefront channel fails NO_CHANNEL before anything is written")
    void failsWhenTheStorefrontChannelIsInactive() {
        jdbc.sql("""
                UPDATE tenant.sales_channels SET status = 'INACTIVE'
                 WHERE tenant_id = :tenantId AND code = :code
                """).param("tenantId", tenantId).param("code", CHANNEL_CODE).update();

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_CHANNEL");
        assertThat(countCatalogs())
                .as("nothing is published to a channel the tenant switched off")
                .isZero();
        assertThat(countOfferings()).isZero();
    }

    /**
     * {@code SampleMenuContent}'s amounts are whole som from
     * {@code tools/seed-data/horecaos-tenant.json}, and nothing converts them.
     * Stamped onto another currency they publish a menu that is wrong by an
     * exchange rate as the platform's own proof that a tenant works.
     */
    @Test
    @DisplayName("a tenant that does not trade in UZS is refused rather than priced in the wrong money")
    void failsWhenTheTenantDoesNotTradeInTheSamplesCurrency() {
        // KZT is a market this platform declares (Markets.KZ), so this is a
        // tenant that can exist rather than a synthetic one.
        setTenantCurrency("KZT");

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("SAMPLE_MENU_UNSUPPORTED_CURRENCY");
        assertThat(countCatalogs()).isZero();
        assertThat(countPriceBooks()).isZero();
        assertThat(countTaxProfiles())
                .as("and the hard-coded Uzbek VAT profile is not written for a tenant it does not describe")
                .isZero();
    }

    /**
     * The refusal the tie produces is permanent, and a permanent condition
     * mapped to {@code RETRY} is five wasted attempts ending in a
     * {@code TRANSIENT_INFRASTRUCTURE} whose console hint tells the operator it
     * retries on its own.
     */
    @Test
    @DisplayName("a tenant's own live price book at priority 0 fails permanently, not as a retry")
    void failsWhenTheSampleBookTiesWithTheTenantsOwn() {
        seedTenantsOwnLivePriceBookAtPriorityZero();

        StepResult result = handler().execute(context());

        assertThat(result.outcome())
                .as("RETRY here would burn every attempt on a condition waiting cannot fix")
                .isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("SAMPLE_PRICING_REFUSED");
        assertThat(publishedCatalogCode())
                .as("and nothing is published on a sample that could not be priced")
                .isEmpty();
    }

    /**
     * The other side of that narrowness, which the tie test cannot see.
     *
     * <p>{@code SampleMenuPricing} catches {@code PriceBookLifecycleException}
     * alone on purpose: {@code PriceAuthoringService.activate} throws
     * {@code OptimisticLockingFailureException} — a sibling type, not a
     * supertype — when two writers race, and any {@code DataAccessException}
     * from {@code setPrice} is transient too. Widen either catch to
     * {@code RuntimeException} and both are reported as a permanent
     * {@code SAMPLE_PRICING_REFUSED}, spending none of the five retries that
     * would have cleared it — and every existing test still passes, because
     * {@code SamplePricingRefusedException} is itself a {@code RuntimeException}.
     *
     * <p>Driven through the real {@code SampleMenuPricing} rather than a stand-in
     * port, because the port is what the widened catch would be behind: a
     * stand-in throwing straight at the handler proves only the handler's own
     * catch. The one thing stood in for is the losing compare-and-set itself —
     * {@code activate} throws exactly this type, at
     * {@code PriceAuthoringService:260}, when {@code activatePriceBook} finds the
     * version moved.
     *
     * <p>Asserted as a throw rather than as {@code RETRY}: {@code execute} has no
     * outer {@code try}, and it is {@code OnboardingService} that maps an escaped
     * handler exception to {@code RETRY/TRANSIENT_INFRASTRUCTURE}.
     */
    @Test
    @DisplayName("a transient pricing failure escapes to the runner instead of becoming a permanent refusal")
    void aTransientPricingFailureIsNotReportedAsARefusal() {
        SampleMenuPricingPort racing = new SampleMenuPricing(priceAuthoringThatLosesTheRace(), pricingStore(), CLOCK);
        var handlerWithRacingPricing = new OrderingOnboardingStepHandlers.SampleMenuPublish(
                jdbc, catalogPort(), racing, stockPort(), new JdbcSalesChannelStore(jdbc));

        assertThatThrownBy(() -> handlerWithRacingPricing.execute(context()))
                .as("a transient pricing failure must be retried, not turned into SAMPLE_PRICING_REFUSED")
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(publishedCatalogCode())
                .as("and nothing is published on a sample that could not be priced")
                .isEmpty();
    }

    /**
     * The currency the book carries is the one the caller passed, not a constant.
     *
     * <p>Asserted on the port rather than through the handler, because the
     * handler now refuses any tenant whose currency is not UZS — so a {@code
     * createSampleBook} that ignored its {@code currency} parameter would be
     * invisible from that side.
     */
    @Test
    @DisplayName("the sample price book carries the currency it was given, not one this module chose")
    void pricesTheSampleInTheCurrencyItIsGiven() {
        SampleMenuPort.SampleMenu menu = catalogPort().installSample(tenantId, brandId, List.of(locationId));

        pricingPort()
                .priceSample(
                        tenantId,
                        brandId,
                        "KZT",
                        menu.variants().stream()
                                .map(variant -> new SampleMenuPricingPort.SampleVariantPrice(
                                        variant.variantId(), variant.amountMinor()))
                                .toList());

        assertThat(priceCurrencies()).containsExactly("KZT");
    }

    @Test
    void failsWhenTheTenantHasNoBrand() {
        jdbc.sql("DELETE FROM tenant.locations WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .update();

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_BRAND");
    }

    @Test
    void failsWhenTheBrandHasNoLocation() {
        jdbc.sql("DELETE FROM tenant.locations WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .update();

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_LOCATION");
    }

    /**
     * A tenant that authored a real menu between starting the run and this step
     * running keeps it. Publishing the sample over it would retire the tenant's
     * own work — the one way this optional step could actually do harm.
     */
    @Test
    @DisplayName("a real menu already published on the storefront is left exactly alone")
    void doesNothingWhenTheBrandAlreadyPublishedItsOwnMenu() {
        UUID realCatalogId = authoring().createCatalog(tenantId, brandId, "MAIN", "Main menu", "uz");
        insertPublication(realCatalogId);

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(result.result()).containsEntry("reason", "MENU_ALREADY_PUBLISHED");
        assertThat(countProducts()).as("not one sample product was created").isZero();
        assertThat(publishedCatalogCode()).contains("MAIN");
    }

    /** The first brand by code, so the choice does not depend on clock resolution. */
    @Test
    @DisplayName("the sample hangs on the first brand by code, and only that one")
    void usesTheFirstBrandByCode() {
        UUID second = UUID.randomUUID();
        insertBrand(second, "AAA-FIRST");
        UUID secondLocation = UUID.randomUUID();
        insertLocation(secondLocation, second, "AAA01");

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(brandOfSampleCatalog()).contains(second);
    }

    // ------------------------------------------------------------------ wiring

    private OrderingOnboardingStepHandlers.SampleMenuPublish handler() {
        return new OrderingOnboardingStepHandlers.SampleMenuPublish(
                jdbc, catalogPort(), pricingPort(), stockPort(), new JdbcSalesChannelStore(jdbc));
    }

    private JdbcCatalogStore catalogStore() {
        return new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
    }

    private JdbcPricingStore pricingStore() {
        return new JdbcPricingStore(jdbc, JsonMapper.builder().build());
    }

    private CatalogAuthoringService authoring() {
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, CLOCK);
        return new CatalogAuthoringService(
                catalogStore(),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                CLOCK);
    }

    private SampleMenuPort catalogPort() {
        CatalogSnapshotLoader loader = new CatalogSnapshotLoader(
                catalogStore(), (tenant, assetIds) -> true, new PricingVariantLookup(pricingStore(), CLOCK), "uz");
        CatalogPublicationService publications = new CatalogPublicationService(
                catalogStore(), new CatalogValidator(), loader, new JdbcSalesChannelStore(jdbc), CLOCK);
        return new SampleMenuService(catalogStore(), authoring(), publications, "uz");
    }

    private SampleMenuPricingPort pricingPort() {
        return new SampleMenuPricing(priceAuthoring(), pricingStore(), CLOCK);
    }

    private PriceAuthoringService priceAuthoring() {
        return new PriceAuthoringService(
                pricingStore(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                new JdbcSalesChannelStore(jdbc),
                CLOCK,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                event -> {},
                // Never read: the sample menu's own activation passes an explicit
                // ActorRef precisely because there is no request behind it. If it
                // ever is read, this throws rather than inventing a person.
                () -> {
                    throw new IllegalStateException(
                            "SAMPLE_MENU_PUBLISH runs from the scheduler and must never read a request actor");
                });
    }

    /**
     * The same service, except that its activation loses the compare-and-set —
     * which is what a second writer produces and what
     * {@code PriceAuthoringService.activate} throws for it. Everything up to the
     * activation is the real thing, so the exception crosses the real
     * {@code SampleMenuPricing} catch on its way out.
     */
    private PriceAuthoringService priceAuthoringThatLosesTheRace() {
        JdbcPricingStore store = pricingStore();
        return new PriceAuthoringService(
                store,
                new JdbcCatalogPricingContext(jdbc, "uz"),
                new JdbcSalesChannelStore(jdbc),
                CLOCK,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                event -> {},
                () -> {
                    throw new IllegalStateException("no request actor here either");
                }) {
            @Override
            public PriceBook activate(
                    UUID tenantId, UUID brandId, UUID priceBookId, int expectedVersion, ActorRef actor) {
                throw new OptimisticLockingFailureException("The price book changed since it was read");
            }
        };
    }

    private StockListingPort stockPort() {
        return new StockListingPortAdapter(inventory());
    }

    private InventoryService inventory() {
        return new InventoryService(new JdbcInventoryStore(jdbc), event -> {}, CLOCK);
    }

    private StorefrontCatalogQuery storefront() {
        return new StorefrontCatalogQuery(
                catalogStore(), new PricingMenuPriceLookup(pricingStore(), new JdbcSalesChannelStore(jdbc), CLOCK));
    }

    private OnboardingStepHandlers.CatalogReadinessValidate catalogReadiness() {
        return new OnboardingStepHandlers.CatalogReadinessValidate(new JdbcTenantControlPlaneStore(jdbc), jdbc);
    }

    private OrderingOnboardingStepHandlers.ActivationSmokeTest smokeTest() {
        JdbcSalesChannelStore channels = new JdbcSalesChannelStore(jdbc);
        DeliveryFeeResolver deliveryFees = new DeliveryFeeResolver(
                new JdbcServiceZoneStore(jdbc),
                new JdbcDeliveryTariffStore(jdbc),
                new JdbcDeliveryFeeResolutionStore(jdbc, JsonMapper.builder().build()),
                (origin, destination, installationId) -> Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        JdbcPromoCodeStore promoCodes =
                new JdbcPromoCodeStore(jdbc, JsonMapper.builder().build());
        QuoteService quotes = new QuoteService(
                pricingStore(),
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channels,
                deliveryFees,
                promoCodes,
                new PromoCodeEligibilityService(promoCodes),
                CLOCK,
                new FakeConfigurationResolver());
        return new OrderingOnboardingStepHandlers.ActivationSmokeTest(
                jdbc,
                channels,
                new ServiceabilityService(new JdbcServiceabilityStore(jdbc), CLOCK),
                quotes,
                inventory(),
                CLOCK);
    }

    private OnboardingStepHandler.StepContext context() {
        return new OnboardingStepHandler.StepContext(UUID.randomUUID(), tenantId, Map.of(), null, 1);
    }

    // ------------------------------------------------------------------ reads

    private int countCatalogs() {
        return count("SELECT count(*) FROM catalog.catalogs WHERE tenant_id = :tenantId");
    }

    private int countProducts() {
        return count("SELECT count(*) FROM catalog.products WHERE tenant_id = :tenantId");
    }

    private int countCategories() {
        return count("SELECT count(*) FROM catalog.categories WHERE tenant_id = :tenantId");
    }

    private int countOfferings() {
        return count("SELECT count(*) FROM catalog.location_offerings "
                + "WHERE tenant_id = :tenantId AND status = 'AVAILABLE'");
    }

    /** Every offering whatever its status, which is what "nothing was duplicated" needs. */
    private int countAllOfferings() {
        return count("SELECT count(*) FROM catalog.location_offerings WHERE tenant_id = :tenantId");
    }

    private int countTaxProfiles() {
        return count("SELECT count(*) FROM pricing.tax_profiles WHERE tenant_id = :tenantId");
    }

    private int countStockItems() {
        return count("SELECT count(*) FROM inventory.stock_items WHERE tenant_id = :tenantId");
    }

    private int countPriceBooks() {
        return count("SELECT count(*) FROM pricing.price_books WHERE tenant_id = :tenantId");
    }

    private int countPublications() {
        return count("SELECT count(*) FROM catalog.publications WHERE tenant_id = :tenantId");
    }

    private int countOpenPrices() {
        return count("SELECT count(*) FROM pricing.prices WHERE tenant_id = :tenantId AND valid_until IS NULL");
    }

    private int count(String sql) {
        Integer value =
                jdbc.sql(sql).param("tenantId", tenantId).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    private List<String> productCodes() {
        return jdbc.sql("SELECT code FROM catalog.products WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .list();
    }

    private List<String> variantSkus() {
        return jdbc.sql("SELECT sku FROM catalog.variants WHERE tenant_id = :tenantId AND sku IS NOT NULL")
                .param("tenantId", tenantId)
                .query(String.class)
                .list();
    }

    private List<String> translatedNames(String locale) {
        return jdbc.sql("""
                SELECT name FROM catalog.translations
                 WHERE tenant_id = :tenantId AND locale = :locale
                   AND entity_type IN ('PRODUCT', 'CATEGORY')
                """)
                .param("tenantId", tenantId)
                .param("locale", locale)
                .query(String.class)
                .list();
    }

    private List<String> priceCurrencies() {
        return jdbc.sql("SELECT DISTINCT currency FROM pricing.price_books WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .list();
    }

    private Optional<String> publishedCatalogCode() {
        return jdbc.sql("""
                SELECT c.code FROM catalog.publications p
                  JOIN catalog.catalogs c ON c.id = p.catalog_id AND c.tenant_id = p.tenant_id
                 WHERE p.tenant_id = :tenantId AND p.status = 'PUBLISHED'
                """).param("tenantId", tenantId).query(String.class).optional();
    }

    /** The sample's variants in SKU order, so a test can name one of them. */
    private List<UUID> sampleVariantIds() {
        return jdbc.sql("SELECT id FROM catalog.variants WHERE tenant_id = :tenantId ORDER BY sku")
                .param("tenantId", tenantId)
                .query(UUID.class)
                .list();
    }

    private void setOfferingStatus(UUID variantId, String status) {
        int updated = jdbc.sql("""
                UPDATE catalog.location_offerings SET status = :status, version = version + 1
                 WHERE tenant_id = :tenantId AND variant_id = :variantId
                """)
                .param("tenantId", tenantId)
                .param("variantId", variantId)
                .param("status", status)
                .update();
        assertThat(updated).as("the fixture has to change a row that exists").isEqualTo(1);
    }

    private String offeringStatus(UUID variantId) {
        return jdbc.sql("""
                SELECT status FROM catalog.location_offerings
                 WHERE tenant_id = :tenantId AND variant_id = :variantId
                """)
                .param("tenantId", tenantId)
                .param("variantId", variantId)
                .query(String.class)
                .single();
    }

    private Optional<UUID> brandOfSampleCatalog() {
        return jdbc.sql("SELECT brand_id FROM catalog.catalogs WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", tenantId)
                .param("code", SampleMenuPort.SAMPLE_CATALOG_CODE)
                .query(UUID.class)
                .optional();
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "t-" + tenantId.toString().substring(0, 8))
                .update();
    }

    private void setTenantCurrency(String currency) {
        jdbc.sql("UPDATE tenant.tenants SET default_currency = :currency WHERE id = :id")
                .param("id", tenantId)
                .param("currency", currency)
                .update();
    }

    /**
     * The tenant's own {@code BRAND}-scope book, live, at priority 0 — which is
     * what {@code PriceAuthoringController} writes when a create request simply
     * omits {@code priority}, not an exotic setup.
     */
    private void seedTenantsOwnLivePriceBookAtPriorityZero() {
        CatalogAuthoringService catalogAuthoring = authoring();
        UUID catalogId = catalogAuthoring.createCatalog(tenantId, brandId, "MAIN", "Main menu", "uz");
        CatalogAuthoringService.ProductCreated product = catalogAuthoring.createProduct(
                tenantId,
                brandId,
                catalogId,
                "MAIN-PLOV",
                "Plov",
                null,
                "uz",
                "MAIN-PLOV",
                "PORTION",
                uz.horecaos.platform.catalog.domain.FiscalClassification.unclassified(),
                null);

        PriceAuthoringService prices = priceAuthoring();
        PriceAuthoringService.PriceBook book = prices.create(
                tenantId,
                brandId,
                new PriceAuthoringService.NewPriceBook("The tenant's own prices", "UZS", null, null, 0));
        prices.assign(
                tenantId,
                brandId,
                book.id(),
                PriceAuthoringService.AssignmentScope.BRAND,
                null,
                new PriceAuthoringService.Assignment(0, null, null));
        prices.setPrice(
                tenantId,
                brandId,
                book.id(),
                uz.horecaos.platform.pricing.application.PriceableType.VARIANT,
                product.defaultVariantId(),
                40_000L);
        prices.activate(
                tenantId,
                brandId,
                book.id(),
                prices.require(tenantId, brandId, book.id()).version(),
                uz.horecaos.platform.audit.api.ActorRef.systemJob("test-fixture"));
    }

    private void insertBrand(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", "b-" + id.toString().substring(0, 8))
                .update();
    }

    private void insertLocation(UUID id, UUID brand, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brand)
                .param("code", code)
                .param("slug", "l-" + id.toString().substring(0, 8))
                .update();
    }

    private UUID insertChannel() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, 'WEB', :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", CHANNEL_CODE)
                .update();
        return id;
    }

    private void bindChannelToLocation(UUID channelId) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("locationId", locationId)
                .update();
    }

    private void enableFulfillmentMode(UUID channelId, String mode) {
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, :mode, true)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("mode", mode)
                .update();
    }

    private void insertPublication(UUID catalogId) {
        jdbc.sql("""
                INSERT INTO catalog.publications
                    (id, tenant_id, brand_id, catalog_id, channel, status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("channel", CHANNEL_CODE)
                .update();
    }
}
