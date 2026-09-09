package uz.horecaos.platform.pos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore.Candidate;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore.LiveRow;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore.ReplaceResult;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0012's stop-list poller, the write side (V0190).
 *
 * <p>Two properties this suite exists to make impossible to get wrong silently:
 * an entity a poll no longer reports must stop being read as constrained (the
 * table's own comment: "absence means unconstrained, not unavailable"), and two
 * replicas racing the same binding must never leave a mix of both replicas'
 * readings standing — see {@link #twoReplicasRacingTheSameBindingConvergeToOneReplicasWholeReadingNeverAMix}
 * for the race this class's own {@code pg_advisory_xact_lock} exists to close.
 */
class JdbcPosLiveAvailabilityStoreTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-7a00-7000-8000-0000000d0001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-7a00-7000-8000-0000000d0002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-7a00-7000-8000-0000000d0003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7a00-7000-8000-0000000d0004");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-7a00-7000-8000-0000000d0005");

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPosLiveAvailabilityStore store;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        store = new JdbcPosLiveAvailabilityStore(jdbc);

        // TRUNCATE CASCADE: this suite's database is its own private clone (see
        // TestDatabase's own doc), so nothing else is disturbed, and CASCADE
        // reaches provider_entity_mappings and catalog.products/variants -- both
        // of which one test in this class seeds directly -- without this method
        // having to enumerate every table that might reference a binding.
        jdbc.sql("TRUNCATE TABLE integration.pos_live_availability, integration.provider_entity_mappings, "
                        + "integration.binding_capabilities, integration.bindings, integration.installations CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("DELETE FROM tenant.locations WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pos-live-availability', 'Legal', 'POS live availability', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'AVAIL_BRAND', 'avail-brand', 'Availability brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :brandId, 'MAIN', 'main', 'Main location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("t", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', 'clopos', 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :t, :installationId, :brandId, :locationId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .update();
    }

    @Test
    @DisplayName("an entity absent from a poll is deleted, not left as still-constrained")
    void absenceDeletesTheStaleRowRatherThanLeavingItConstrained() {
        // First poll: two products out of stock. Nothing existed before, so
        // both are reported as newly out of stock and nothing is newly back.
        ReplaceResult first = store.replace(
                TENANT,
                BINDING,
                List.of(availability("ext-A", BigDecimal.ZERO), availability("ext-B", BigDecimal.ZERO)),
                NOW);
        assertThat(first.newlyOutOfStock()).containsExactlyInAnyOrder("ext-A", "ext-B");
        assertThat(first.newlyBackInStock()).isEmpty();
        assertThat(ids(store.currentReading(TENANT, BINDING))).containsExactlyInAnyOrder("ext-A", "ext-B");

        // Second poll: the provider no longer reports ext-B at all. Per the
        // table's own rule, that means ext-B is unconstrained again -- its row
        // must be gone, not merely unread.
        ReplaceResult second =
                store.replace(TENANT, BINDING, List.of(availability("ext-A", BigDecimal.ZERO)), NOW.plusSeconds(45));

        assertThat(second.newlyBackInStock())
                .as("ext-B dropping out of the stop list is a real 'back in stock' signal")
                .containsExactly("ext-B");
        assertThat(second.newlyOutOfStock())
                .as("ext-A was already out of stock; nothing changed for it")
                .isEmpty();

        List<String> remaining = ids(store.currentReading(TENANT, BINDING));
        assertThat(remaining)
                .as("a row this poll did not report must not survive it -- inverting this empties every menu "
                        + "the moment a restock ever happens")
                .containsExactly("ext-A")
                .doesNotContain("ext-B");
    }

    @Test
    @DisplayName("an empty poll response clears every row for the binding")
    void anEmptyResponseClearsEverything() {
        store.replace(TENANT, BINDING, List.of(availability("ext-A", BigDecimal.ZERO)), NOW);

        ReplaceResult cleared = store.replace(TENANT, BINDING, List.of(), NOW.plusSeconds(45));

        assertThat(cleared.newlyBackInStock()).containsExactly("ext-A");
        assertThat(store.currentReading(TENANT, BINDING)).isEmpty();
    }

    @Test
    @DisplayName("a positive stock limit is not treated as out of stock")
    void aPositiveLimitIsNotOutOfStock() {
        ReplaceResult result = store.replace(TENANT, BINDING, List.of(availability("ext-A", new BigDecimal("5"))), NOW);

        assertThat(result.newlyOutOfStock())
                .as("a limit above zero is a cap, not a stock-out")
                .isEmpty();
        assertThat(result.newlyBackInStock()).isEmpty();
        assertThat(store.currentReading(TENANT, BINDING)).hasSize(1);
    }

    @Test
    @DisplayName("two replicas racing the same binding converge to one replica's whole reading, never a mix of both")
    void twoReplicasRacingTheSameBindingConvergeToOneReplicasWholeReadingNeverAMix() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));

        List<CatalogSnapshot.Availability> readingX =
                List.of(availability("ext-A", BigDecimal.ZERO), availability("ext-B", BigDecimal.ZERO));
        List<CatalogSnapshot.Availability> readingY =
                List.of(availability("ext-A", BigDecimal.ZERO), availability("ext-C", BigDecimal.ZERO));

        inParallel(
                () -> transactions.execute(status -> store.replace(TENANT, BINDING, readingX, NOW)),
                () -> transactions.execute(status -> store.replace(TENANT, BINDING, readingY, NOW.plusSeconds(1))));

        Set<String> finalIds = Set.copyOf(ids(store.currentReading(TENANT, BINDING)));

        assertThat(Set.of(Set.of("ext-A", "ext-B"), Set.of("ext-A", "ext-C")))
                .as("the surviving rows must be exactly one replica's whole reading -- a mix such as "
                        + "{A, B, C} or a partial one such as {A} would mean the advisory lock in "
                        + "JdbcPosLiveAvailabilityStore#replace did not serialise the two replicas' "
                        + "delete-then-upsert against each other")
                .contains(finalIds);
    }

    @Test
    @DisplayName("eligibleBindings lists only active bindings with AVAILABILITY_READ enabled")
    void eligibleBindingsFiltersByStatusAndCapability() {
        assertThat(store.eligibleBindings())
                .as("no binding has the capability enabled yet")
                .isEmpty();

        jdbc.sql("""
                INSERT INTO integration.binding_capabilities (binding_id, tenant_id, capability_code, enabled)
                VALUES (:bindingId, :tenantId, 'AVAILABILITY_READ', true)
                """).param("bindingId", BINDING).param("tenantId", TENANT).update();

        List<Candidate> candidates = store.eligibleBindings();
        assertThat(candidates)
                .filteredOn(c -> c.tenantId().equals(TENANT))
                .hasSize(1)
                .first()
                .satisfies(c -> {
                    assertThat(c.bindingId()).isEqualTo(BINDING);
                    assertThat(c.locationId()).isEqualTo(LOCATION);
                    assertThat(c.providerType()).isEqualTo("clopos");
                });

        jdbc.sql("UPDATE integration.binding_capabilities SET enabled = false WHERE binding_id = :b")
                .param("b", BINDING)
                .update();
        assertThat(store.eligibleBindings())
                .filteredOn(c -> c.tenantId().equals(TENANT))
                .isEmpty();

        jdbc.sql("UPDATE integration.binding_capabilities SET enabled = true WHERE binding_id = :b")
                .param("b", BINDING)
                .update();
        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :b")
                .param("b", BINDING)
                .update();
        assertThat(store.eligibleBindings())
                .as("a suspended binding must not be polled")
                .filteredOn(c -> c.tenantId().equals(TENANT))
                .isEmpty();
    }

    @Test
    @DisplayName("resolveDefaultVariants resolves a mapped product to its default variant, and skips an unmapped one")
    void resolveDefaultVariantsFollowsTheAdr0011Mapping() {
        UUID product = UUID.randomUUID();
        UUID defaultVariant = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :t, :brandId, 'OSH', 'ACTIVE')
                """)
                .param("id", product)
                .param("t", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :t, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", defaultVariant)
                .param("t", TENANT)
                .param("brandId", BRAND)
                .param("productId", product)
                .update();
        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                     external_entity_id, status, mapping_source)
                VALUES (:id, :t, :installationId, :bindingId, 'VARIANT_PARENT', :productId,
                        'ext-A', 'ACTIVE', 'DISCOVERED')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("bindingId", BINDING)
                .param("productId", product)
                .update();

        Map<String, UUID> resolved = store.resolveDefaultVariants(TENANT, BINDING, Set.of("ext-A", "ext-unmapped"));

        assertThat(resolved).containsExactly(Map.entry("ext-A", defaultVariant));
    }

    private static CatalogSnapshot.Availability availability(String externalId, BigDecimal stockLimit) {
        return new CatalogSnapshot.Availability(externalId, stockLimit, NOW, Map.of("id", externalId));
    }

    private static List<String> ids(List<LiveRow> rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.externalId()));
        return ids;
    }

    private static <T> List<T> inParallel(Callable<T> first, Callable<T> second) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = pool.invokeAll(List.of(first, second));
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
