package uz.qoida.platform.pos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

import uz.qoida.platform.pos.domain.CatalogSnapshot;
import uz.qoida.platform.pos.domain.SourceKind;
import uz.qoida.platform.pos.domain.SyncConflict;
import uz.qoida.platform.pos.domain.SyncDifference;
import uz.qoida.platform.pos.domain.SyncDifference.EntityType;
import uz.qoida.platform.support.TestDatabase;

/**
 * What the staging writes say, and how many statements they take to say it
 * (ADR 0012).
 *
 * <p>The second half is the point. Every assertion about content here also held
 * when the store wrote a row per statement; what it could not hold was a sync run
 * over a real brand's catalog, which was thousands of round-trips inside one
 * transaction against the database that is also taking orders. So the statement
 * count is asserted directly, through a counting connection, rather than inferred
 * from a stopwatch that would be flaky on a loaded build agent.
 */
class JdbcPosSyncStoreTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121901");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121902");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121903");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121904");

    private static final Instant NOW = Instant.parse("2026-08-22T02:00:00Z");

    /** Past the store's 500-row fold, so the chunk boundary is exercised. */
    private static final int OVER_ONE_CHUNK = 601;

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcPosSyncStore store;
    private final AtomicInteger statements = new AtomicInteger();
    private UUID runId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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

        jdbc = JdbcClient.create(counting(dataSource, statements));
        store = new JdbcPosSyncStore(jdbc, JsonMapper.builder().build());

        jdbc.sql("DELETE FROM integration.pos_absence_observations WHERE tenant_id = :t")
                .param("t", TENANT).update();
        jdbc.sql("DELETE FROM integration.pos_sync_runs WHERE tenant_id = :t")
                .param("t", TENANT).update();
        jdbc.sql("DELETE FROM integration.bindings WHERE tenant_id = :t")
                .param("t", TENANT).update();
        jdbc.sql("DELETE FROM integration.installations WHERE tenant_id = :t")
                .param("t", TENANT).update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t").param("t", TENANT).update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version)
                VALUES (:id, 'pos-sync-store', 'Legal', 'POS sync store', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands
                    (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'POS_BRAND', 'pos-brand', 'POS brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status)
                VALUES (:id, :t, 'POS', 'clopos', 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING).param("t", TENANT)
                .param("installationId", INSTALLATION).param("brandId", BRAND)
                .update();

        runId = store.openRun(TENANT, BINDING, "SCHEDULED", true, "clopos-1", 1, NOW);
    }

    // ------------------------------------------------------------------ staging

    @Test
    @DisplayName("a catalog larger than one chunk is staged whole")
    void everyRowArrivesWhateverTheChunkBoundaryFallsOn() {
        store.stage(TENANT, runId, snapshot(OVER_ONE_CHUNK));

        assertThat(staged("pos_staged_categories")).isEqualTo(OVER_ONE_CHUNK);
        assertThat(staged("pos_staged_products")).isEqualTo(OVER_ONE_CHUNK);
        assertThat(staged("pos_staged_variants")).isEqualTo(OVER_ONE_CHUNK);
        assertThat(staged("pos_staged_modifier_groups")).isEqualTo(OVER_ONE_CHUNK);
        assertThat(staged("pos_staged_modifiers")).isEqualTo(OVER_ONE_CHUNK);
        assertThat(staged("pos_staged_availability")).isEqualTo(OVER_ONE_CHUNK);

        // Including the row on the far side of the fold, which is the one a
        // chunking bug loses without changing any count a spot check would look at.
        assertThat(jdbc.sql("""
                        SELECT name FROM integration.pos_staged_products
                         WHERE run_id = :runId AND external_entity_id = :id
                        """)
                .param("runId", runId).param("id", "P-600")
                .query(String.class).single())
                .isEqualTo("Product 600");
    }

    @Test
    @DisplayName("staging a catalog is a handful of statements, not one per entity")
    void theRunDoesNotSpendThousandsOfRoundTripsOnOneSnapshot() {
        statements.set(0);
        store.stage(TENANT, runId, snapshot(OVER_ONE_CHUNK));

        // Six clears, two chunks for each of six entity types, and the counter
        // update: nineteen. Asserted loosely enough not to break on a seventh
        // staged table, and tightly enough that a row per entity — 3606 of them
        // here — fails.
        assertThat(statements.get())
                .as("one statement per staged entity is a network round-trip per entity, "
                        + "inside one transaction, on the database taking orders")
                .isLessThanOrEqualTo(32);
    }

    @Test
    @DisplayName("a resumed run stages the same rows once, not twice")
    void stagingIsStillIdempotent() {
        store.stage(TENANT, runId, snapshot(OVER_ONE_CHUNK));
        store.stage(TENANT, runId, snapshot(OVER_ONE_CHUNK));

        assertThat(staged("pos_staged_products")).isEqualTo(OVER_ONE_CHUNK);
    }

    @Test
    @DisplayName("a provider that sent one id twice keeps the first row, as it did per statement")
    void aDuplicateInsideOneChunkIsSkippedAndNotAppliedOverTheFirst() {
        CatalogSnapshot snapshot = new CatalogSnapshot(NOW, true, 1, List.of(), List.of(
                product("P-1", "The row the walk saw first"),
                product("P-1", "The row the same walk saw again")),
                List.of(), List.of(), List.of(), List.of());

        store.stage(TENANT, runId, snapshot);

        // ON CONFLICT DO NOTHING resolves a repeat inside one statement exactly as
        // it resolved a repeat across two: the first write stands. A DO UPDATE
        // here would be the paging race overwriting the row it raced with.
        assertThat(staged("pos_staged_products")).isOne();
        assertThat(jdbc.sql("""
                        SELECT name FROM integration.pos_staged_products WHERE run_id = :runId
                        """)
                .param("runId", runId).query(String.class).single())
                .isEqualTo("The row the walk saw first");
    }

    // ----------------------------------------------------------------- absences

    @Test
    @DisplayName("a streak grows for what is missing and is cleared for what came back")
    void absencesAreRecordedInBulkWithoutChangingTheQuorumRule() {
        Set<String> mapped = ids("P-", 3);
        store.recordAbsences(TENANT, BINDING, runId, Map.of(EntityType.PRODUCT, mapped),
                Map.of(EntityType.PRODUCT, Set.of("P-0")), false, NOW);

        UUID second = store.openRun(TENANT, BINDING, "SCHEDULED", true, "clopos-1", 1, NOW);
        var history = store.recordAbsences(TENANT, BINDING, second,
                Map.of(EntityType.PRODUCT, mapped),
                Map.of(EntityType.PRODUCT, Set.of("P-0")), true, NOW);

        // The engine is handed the streak before this run, and adds the current
        // absence itself.
        assertThat(history.byType().get(EntityType.PRODUCT))
                .containsEntry("P-1", new uz.qoida.platform.pos.domain.DifferenceEngine
                        .AbsenceHistory.Streak(1, false))
                .containsEntry("P-2", new uz.qoida.platform.pos.domain.DifferenceEngine
                        .AbsenceHistory.Streak(1, false))
                .doesNotContainKey("P-0");

        // One unstable walk in the streak makes the whole streak unstable, and
        // folding the upserts into one statement must not lose that fold.
        assertThat(jdbc.sql("""
                        SELECT bool_and(all_walks_stable) FROM integration.pos_absence_observations
                         WHERE tenant_id = :t AND binding_id = :b
                        """)
                .param("t", TENANT).param("b", BINDING).query(Boolean.class).single())
                .isFalse();
    }

    @Test
    @DisplayName("a binding's whole mapped set is a handful of statements")
    void absenceRecordingDoesNotSpendAStatementPerMappedEntity() {
        Set<String> mapped = ids("P-", OVER_ONE_CHUNK);
        Set<String> present = ids("P-", 200);

        statements.set(0);
        store.recordAbsences(TENANT, BINDING, runId, Map.of(EntityType.PRODUCT, mapped),
                Map.of(EntityType.PRODUCT, present), true, NOW);

        // One delete for the reappeared, one chunk per five hundred absent, and
        // the read back.
        assertThat(statements.get()).isLessThanOrEqualTo(8);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM integration.pos_absence_observations
                         WHERE tenant_id = :t AND binding_id = :b
                        """)
                .param("t", TENANT).param("b", BINDING).query(Integer.class).single())
                .isEqualTo(OVER_ONE_CHUNK - 200);
    }

    // ----------------------------------------------------------------- findings

    @Test
    @DisplayName("a comparison's findings are written in bulk and stay idempotent")
    void findingsSurviveAReRunOfTheComparison() {
        List<SyncDifference> differences = new ArrayList<>();
        List<SyncConflict> conflicts = new ArrayList<>();
        for (int index = 0; index < OVER_ONE_CHUNK; index++) {
            differences.add(new SyncDifference(EntityType.PRODUCT, "P-" + index, null,
                    SyncDifference.DifferenceCategory.ADDITION, null, null, "imported",
                    SyncDifference.FieldAuthority.PROVIDER, SyncDifference.Severity.INFO,
                    SyncDifference.RecommendedAction.REVIEW, null));
            conflicts.add(new SyncConflict(EntityType.PRODUCT, "P-" + index,
                    SyncConflict.Kind.DUPLICATE_EXTERNAL_ID, "seen twice in one walk", List.of()));
        }

        statements.set(0);
        store.recordFindings(TENANT, runId, differences, conflicts);
        int firstPass = statements.get();

        store.recordFindings(TENANT, runId, differences, conflicts);

        assertThat(count("pos_sync_differences")).isEqualTo(OVER_ONE_CHUNK);
        assertThat(count("pos_sync_conflicts")).isEqualTo(OVER_ONE_CHUNK);
        assertThat(firstPass).isLessThanOrEqualTo(8);
        assertThat(store.differences(TENANT, runId, 5, 0)).hasSize(5);
    }

    // ---------------------------------------------------------------- fixtures

    private CatalogSnapshot snapshot(int each) {
        List<CatalogSnapshot.Category> categories = new ArrayList<>();
        List<CatalogSnapshot.Product> products = new ArrayList<>();
        List<CatalogSnapshot.Variant> variants = new ArrayList<>();
        List<CatalogSnapshot.ModifierGroup> groups = new ArrayList<>();
        List<CatalogSnapshot.Modifier> modifiers = new ArrayList<>();
        List<CatalogSnapshot.Availability> availability = new ArrayList<>();

        for (int index = 0; index < each; index++) {
            categories.add(new CatalogSnapshot.Category("C-" + index, null, "Category " + index,
                    index, true, 1, Map.of("id", index)));
            products.add(product("P-" + index, "Product " + index));
            variants.add(new CatalogSnapshot.Variant("V-" + index, "P-" + index, "Variant " + index,
                    12_000L, "UZS", true, "unit-" + index, Map.of("id", index)));
            groups.add(new CatalogSnapshot.ModifierGroup("G-" + index, "P-" + index,
                    "Group " + index, 0, 1, false, Map.of("id", index)));
            modifiers.add(new CatalogSnapshot.Modifier("M-" + index, "G-" + index,
                    "Modifier " + index, null, null, true, Map.of("id", index)));
            availability.add(new CatalogSnapshot.Availability("P-" + index,
                    java.math.BigDecimal.valueOf(index), NOW, Map.of("id", index)));
        }
        return new CatalogSnapshot(NOW, true, 3, categories, products, variants, groups, modifiers,
                availability);
    }

    private static CatalogSnapshot.Product product(String externalId, String name) {
        return new CatalogSnapshot.Product(externalId, name, null, SourceKind.DISH, true, false,
                45_000L, "UZS", true, false, null, Map.of("name", name));
    }

    private static Set<String> ids(String prefix, int count) {
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            ids.add(prefix + index);
        }
        return ids;
    }

    private int staged(String table) {
        return jdbc.sql("SELECT count(*) FROM integration.%s WHERE run_id = :runId".formatted(table))
                .param("runId", runId).query(Integer.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM integration.%s WHERE run_id = :runId".formatted(table))
                .param("runId", runId).query(Integer.class).single();
    }

    /**
     * A data source that counts the statements prepared through it.
     *
     * <p>A proxy rather than a driver-level log because the assertion is about how
     * many times this code goes to the database, which is the thing that got worse
     * with catalog size — and because a timing assertion would be flaky on a build
     * agent running three other suites.
     */
    private static DataSource counting(DataSource delegate, AtomicInteger prepared) {
        ClassLoader loader = JdbcPosSyncStoreTests.class.getClassLoader();
        return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(method, delegate, arguments);
                    if (!(result instanceof Connection connection)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(loader, new Class<?>[] {Connection.class},
                            (connectionProxy, connectionMethod, connectionArguments) -> {
                                if (connectionMethod.getName().startsWith("prepare")) {
                                    prepared.incrementAndGet();
                                }
                                return invoke(connectionMethod, connection, connectionArguments);
                            });
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            // Unwrapped, or a SQLException the store expects to catch arrives as an
            // UndeclaredThrowableException instead.
            throw wrapped.getCause();
        }
    }
}
