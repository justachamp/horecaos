package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcFavouriteStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A customer's shortlist (V0097).
 *
 * <p>What matters here is what a list does <em>not</em> contain: another
 * customer's marks, another brand's, and a product that is not this brand's at
 * all. A test that only proved a mark round-trips would pass against a store
 * with no account predicate, which is the way this leaks.
 */
class FavouriteStoreTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcFavouriteStore store;
    private UUID alice;
    private UUID bob;
    private UUID osh;
    private UUID somsa;
    private UUID otherBrandsDish;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for favourite tests");
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
        jdbc.sql("TRUNCATE TABLE customer.favourites CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seed();
        store = new JdbcFavouriteStore(jdbc);
    }

    @Test
    @DisplayName("a mark round-trips, newest first")
    void marksComeBackNewestFirst() {
        assertThat(store.add(TENANT, BRAND, alice, osh)).isTrue();
        assertThat(store.add(TENANT, BRAND, alice, somsa)).isTrue();

        // The two inserts land inside one transaction, so now() gives them the
        // same timestamp and the order would fall through to product_id -- which
        // is a random UUID. Stamping them apart is what makes this assert the
        // ordering rather than a coin flip: without it the test passed even with
        // the ORDER BY removed entirely.
        stampCreatedAt(osh, "2026-08-21T10:00:00Z");
        stampCreatedAt(somsa, "2026-08-21T11:00:00Z");

        assertThat(store.list(TENANT, BRAND, alice))
                .as("created_at DESC, so the most recently marked leads")
                .containsExactly(somsa, osh);

        // And the other way round, so the assertion cannot be satisfied by any
        // fixed order that happens to match once.
        stampCreatedAt(osh, "2026-08-21T12:00:00Z");
        assertThat(store.list(TENANT, BRAND, alice)).containsExactly(osh, somsa);
    }

    private void stampCreatedAt(UUID productId, String instant) {
        jdbc.sql("""
                UPDATE customer.favourites SET created_at = CAST(:at AS timestamptz)
                WHERE account_id = :accountId AND product_id = :productId
                """)
                .param("at", instant)
                .param("accountId", alice)
                .param("productId", productId)
                .update();
    }

    @Test
    @DisplayName("marking twice is one fact, not an error")
    void markingTwiceIsIdempotent() {
        assertThat(store.add(TENANT, BRAND, alice, osh)).isTrue();
        assertThat(store.add(TENANT, BRAND, alice, osh))
                .as("a double tap or a retried request must not fail")
                .isTrue();

        assertThat(store.list(TENANT, BRAND, alice)).containsExactly(osh);
    }

    @Test
    @DisplayName("one customer's list is not another's")
    void listsAreScopedToTheirAccount() {
        store.add(TENANT, BRAND, alice, osh);
        store.add(TENANT, BRAND, bob, somsa);

        assertThat(store.list(TENANT, BRAND, alice)).containsExactly(osh);
        assertThat(store.list(TENANT, BRAND, bob)).containsExactly(somsa);
    }

    @Test
    @DisplayName("a product of another brand cannot be marked")
    void aForeignProductIsRefused() {
        assertThat(store.add(TENANT, BRAND, alice, otherBrandsDish))
                .as("the foreign key carries the brand, so this cannot become an "
                        + "unresolvable row sitting in a customer's list forever")
                .isFalse();
        assertThat(store.list(TENANT, BRAND, alice)).isEmpty();
    }

    @Test
    @DisplayName("removing what was never marked is not an error")
    void removingSomethingUnmarkedIsFine() {
        store.remove(TENANT, BRAND, alice, osh);
        assertThat(store.list(TENANT, BRAND, alice)).isEmpty();

        store.add(TENANT, BRAND, alice, osh);
        store.remove(TENANT, BRAND, alice, osh);
        assertThat(store.list(TENANT, BRAND, alice)).isEmpty();
    }

    @Test
    @DisplayName("deleting the account takes its marks with it")
    void marksGoWithTheAccount() {
        store.add(TENANT, BRAND, alice, osh);

        jdbc.sql("DELETE FROM customer.customer_accounts WHERE id = :id")
                .param("id", alice)
                .update();

        assertThat(store.list(TENANT, BRAND, alice))
                .as("a behavioural record tied to an account is deleted with it")
                .isEmpty();
    }

    private void seed() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'fav-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        for (var brand : List.of(new Object[] {BRAND, "MAIN", "main"}, new Object[] {OTHER_BRAND, "OTHER", "other"})) {
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status,
                        version)
                    VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                    """)
                    .param("id", brand[0])
                    .param("tenantId", TENANT)
                    .param("code", brand[1])
                    .param("slug", brand[2])
                    .update();
        }
        alice = account("Alice");
        bob = account("Bob");
        osh = product(BRAND, "PLOV");
        somsa = product(BRAND, "SOMSA");
        otherBrandsDish = product(OTHER_BRAND, "OTHER_DISH");
    }

    /**
     * A customer account is tenant-scoped, with an optional brand partition
     * (ADR 0051) -- it is not a per-brand row. The brand on a favourite is the
     * brand whose menu the product belongs to, which is a different thing and
     * is why the two are separate columns.
     */
    private UUID account(String label) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (
                    id, tenant_id, identity_partition_brand_id, status, display_name, version)
                VALUES (:id, :tenantId, :brandId, 'ACTIVE', :label, 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("label", label)
                .update();
        return id;
    }

    private UUID product(UUID brandId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        return id;
    }
}
