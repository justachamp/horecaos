package uz.horecaos.platform.ordering.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code ordering.approval_decisions}' own "who tried to reject this and
 * when" (gap map row 1.2b), against a real PostgreSQL — the {@code
 * ux_approval_effective_per_order} partial unique index is what makes "at
 * most one decision is ever effective" true, and the query under test is what
 * the order detail's timeline reads to show the loser {@link
 * JdbcOrderStore#findEffectiveDecision} alone cannot.
 */
class JdbcOrderStoreApprovalDecisionsTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcOrderStore orders;
    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID orderId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("""
                TRUNCATE TABLE
                    ordering.approval_decisions,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        orders = new JdbcOrderStore(jdbc);
        seedTenancy();
        orderId = seedConfirmedOrder();
    }

    @Test
    @DisplayName("every decision is readable, winner and losers alike -- the timeline's own question")
    void decisionsOfReturnsEveryRowInIssuedOrder() {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        orders.insertApprovalDecision(
                UUID.randomUUID(),
                TENANT,
                orderId,
                "click-1",
                "REJECT",
                "HORECAOS_OPERATIONS",
                "USER",
                "op-a",
                "OUT_OF_STOCK",
                t0);
        UUID winnerId = UUID.randomUUID();
        orders.insertApprovalDecision(
                winnerId,
                TENANT,
                orderId,
                "click-2",
                "APPROVE",
                "HORECAOS_OPERATIONS",
                "USER",
                "op-b",
                null,
                t0.plusSeconds(1));
        assertThat(orders.markDecisionEffective(TENANT, winnerId)).isTrue();

        var decisions = orders.decisionsOf(TENANT, orderId);

        assertThat(decisions).hasSize(2);
        assertThat(decisions)
                .extracting(JdbcOrderStore.ApprovalDecisionRow::decisionId)
                .containsExactly("click-1", "click-2");
        assertThat(decisions)
                .filteredOn(row -> "click-1".equals(row.decisionId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.action()).isEqualTo("REJECT");
                    assertThat(row.effective())
                            .as("the loser of the compare-and-set is not the effective decision")
                            .isFalse();
                });
        assertThat(decisions)
                .filteredOn(row -> "click-2".equals(row.decisionId()))
                .singleElement()
                .satisfies(row -> assertThat(row.effective()).isTrue());
    }

    @Test
    @DisplayName("an order with no decisions at all answers an empty list, not null")
    void decisionsOfAnswersEmptyForAnUndecidedOrder() {
        assertThat(orders.decisionsOf(TENANT, orderId)).isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", TENANT)
                .param("slug", "approval-decisions-tenant")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                """)
                .param("id", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        UUID publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();

        this.publicationId = publicationId;
        this.channelId = channelId;
    }

    private UUID seedConfirmedOrder() {
        UUID id = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String reference = "approval-decisions-1";

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("publicationId", publicationId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'PICKUP', 'UZS',
                        'ACTIVE', :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'PICKUP', 'AUTO_CONFIRM', 0, 'NONE', 'CONFIRMED', 'UZS',
                        50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", id)
                .param("number", "APD-1")
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        return id;
    }
}
