package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.ordering.api.OrderReceiptSource;
import uz.horecaos.platform.ordering.application.OrderReceiptReaderFactory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.application.QuoteTaxReader;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.support.ReceiptOrderSeed;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What a receipt is built from, read from the rows the platform itself writes (ADR 0166 Decision 9).
 *
 * <p>The itemisation arrives from the pricing engine and the checkout and amendment paths, which
 * {@code PricingEngineTests} and {@code ComboOrderFlowEndToEndTests} drive for real; this reads the rows
 * back the way the receipt builder does, including the cases a fresh checkout cannot produce any more (an
 * order from before options were itemised), and the tenant boundary.
 */
class OrderReceiptReaderTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    private OrderReceiptSource reader;
    private UUID orderId;
    private UUID quoteId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        db = TestDatabase.migrated();
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.modifier_options, catalog.modifier_groups, catalog.variants, "
                        + "catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        for (UUID tenant : new UUID[] {TENANT, OTHER_TENANT}) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status)
                    VALUES (:id, :slug, 'Reader Tests LLC', 'Reader Tests', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                    """).param("id", tenant).param("slug", "reader-" + tenant).update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :t, 'BRAND1', 'brand-one', 'Brand One', 'ACTIVE')
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :t, :b, 'LOC1', 'location-one', 'Location One', 'Asia/Tashkent', 'ACTIVE')
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

        quoteId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MENU', 'Menu', 'ACTIVE')
                """).param("id", catalog).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :t, :b, :c, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("c", catalog)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor, tax_minor,
                    total_minor, expires_at)
                VALUES (:id, :t, :b, :l, 'UZS', 'ACTIVE', :p, 3, 'hash', 44643, 5357, 50000,
                    now() + interval '1 day')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("p", publication)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    guest_reference_hash, fulfillment_mode, currency, status, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, expires_at)
                VALUES (:id, :t, :b, :l, :ch, 'guest', 'DELIVERY', 'UZS', 'CHECKOUT_IN_PROGRESS', :q, 'hash',
                    :p, now() + interval '1 day')
                """)
                .param("id", cart)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("ch", channel)
                .param("q", quoteId)
                .param("p", publication)
                .update();
        // 50 000 of goods and a delivery fee of 8 000, less a 3 000 order discount: 55 000.
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency, subtotal_minor,
                    tax_minor, discount_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, confirmed_at,
                    closed_at)
                VALUES (:id, 'R-1', :t, :b, :l, :ch, 'WEB', 'guest', 'DELIVERY', 'AUTO_CONFIRM', 'NONE',
                    'COMPLETED', 'UZS', 44643, 5357, 3000, 8000, 55000, :q, 'hash', :p, :cart, :key, now(),
                    now())
                """)
                .param("id", orderId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("ch", channel)
                .param("q", quoteId)
                .param("p", publication)
                .param("cart", cart)
                .param("key", UUID.randomUUID().toString())
                .update();

        JdbcOrderStore orders = new JdbcOrderStore(jdbc);
        QuoteTaxReader taxes = new QuoteTaxReader(
                new JdbcPricingStore(jdbc, JsonMapper.builder().build()));
        reader = OrderReceiptReaderFactory.create(orders, taxes);
    }

    @Test
    @DisplayName(
            "a line carries the options it was itemised with, a chosen one and a hidden one, and the variant's part is what is left")
    void aLineCarriesItsItemisedOptions() {
        ReceiptOrderSeed.quoteTax(jdbc, TENANT, quoteId, 1200, "INCLUSIVE");
        UUID dish = ReceiptOrderSeed.variant(jdbc, TENANT, BRAND, "PLOV");
        UUID cheese = ReceiptOrderSeed.modifierOption(jdbc, TENANT, BRAND, "CHEESE");
        UUID box = ReceiptOrderSeed.modifierOption(jdbc, TENANT, BRAND, "BOX");
        UUID line = ReceiptOrderSeed.orderLine(
                jdbc, TENANT, orderId, 1, dish, "Plov", BigDecimal.valueOf(2), 50_000, 47_000, 5_357);
        ReceiptOrderSeed.optionCharge(jdbc, TENANT, orderId, 1, line, cheese, 10_000);
        ReceiptOrderSeed.adjustment(
                jdbc,
                TENANT,
                orderId,
                2,
                line,
                "MODIFIER",
                QuoteSnapshot.Adjustment.HIDDEN_MODIFIER_SOURCE,
                box,
                4_000);
        // A hidden option applied at no charge is on the order and is no receipt line.
        UUID free = ReceiptOrderSeed.modifierOption(jdbc, TENANT, BRAND, "NAPKIN");
        ReceiptOrderSeed.adjustment(
                jdbc, TENANT, orderId, 3, line, "MODIFIER", QuoteSnapshot.Adjustment.HIDDEN_MODIFIER_SOURCE, free, 0);

        var order = reader.find(TENANT, orderId).orElseThrow();

        assertThat(order.feeMinor()).isEqualTo(8_000);
        assertThat(order.discountMinor()).isEqualTo(3_000);
        assertThat(order.totalMinor()).isEqualTo(55_000);
        assertThat(order.tax())
                .as("the rule the accepted quote extracted VAT under")
                .isEqualTo(new OrderReceiptSource.Tax(1200, true));
        var read = order.lines().getFirst();
        assertThat(read.baseMinor()).isEqualTo(50_000);
        assertThat(read.finalMinor()).isEqualTo(47_000);
        assertThat(read.quantity()).isEqualByComparingTo("2");
        assertThat(read.options())
                .extracting(OrderReceiptSource.Option::optionId, OrderReceiptSource.Option::amountMinor)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(cheese, 10_000L),
                        org.assertj.core.groups.Tuple.tuple(box, 4_000L));
        assertThat(read.options()).filteredOn(OrderReceiptSource.Option::hidden).hasSize(1);
        assertThat(read.unitemisedModifiersMinor()).isZero();
        assertThat(read.catchweight()).isNull();
    }

    @Test
    @DisplayName(
            "an order from before options were itemised reports the lump it cannot split, and never invents a split")
    void anOldOrderReportsWhatItCannotSplit() {
        ReceiptOrderSeed.quoteTax(jdbc, TENANT, quoteId, 1200, "INCLUSIVE");
        UUID dish = ReceiptOrderSeed.variant(jdbc, TENANT, BRAND, "PLOV");
        UUID line = ReceiptOrderSeed.orderLine(
                jdbc, TENANT, orderId, 1, dish, "Plov", BigDecimal.ONE, 50_000, 47_000, 5_357);
        // The step an older quote wrote: one «MODIFIERS» figure for the whole line, against the price book.
        ReceiptOrderSeed.adjustment(jdbc, TENANT, orderId, 1, line, "MODIFIER", "PRICE_BOOK", UUID.randomUUID(), 6_000);

        var read = reader.find(TENANT, orderId).orElseThrow().lines().getFirst();

        assertThat(read.options()).isEmpty();
        assertThat(read.unitemisedModifiersMinor()).isEqualTo(6_000);
    }

    @Test
    @DisplayName("a quote that recorded no tax rule reads as none: a rate is not guessed")
    void aQuoteWithNoTaxRuleReadsAsNone() {
        UUID dish = ReceiptOrderSeed.variant(jdbc, TENANT, BRAND, "PLOV");
        ReceiptOrderSeed.orderLine(jdbc, TENANT, orderId, 1, dish, "Plov", BigDecimal.ONE, 50_000, 47_000, 5_357);

        assertThat(reader.find(TENANT, orderId).orElseThrow().tax()).isNull();
    }

    @Test
    @DisplayName("an exclusive rule reads as exclusive")
    void anExclusiveRuleReadsAsExclusive() {
        ReceiptOrderSeed.quoteTax(jdbc, TENANT, quoteId, 1200, "EXCLUSIVE");

        assertThat(reader.find(TENANT, orderId).orElseThrow().tax()).isEqualTo(new OrderReceiptSource.Tax(1200, false));
    }

    @Test
    @DisplayName("another tenant's order does not exist")
    void anotherTenantsOrderDoesNotExist() {
        assertThat(reader.find(OTHER_TENANT, orderId)).isEmpty();
    }
}
