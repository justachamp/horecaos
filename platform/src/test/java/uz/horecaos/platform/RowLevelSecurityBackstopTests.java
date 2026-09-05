package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.rls.JdbcTenantRlsSession;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0056's backstop, proven rather than asserted, on the one schema V0161
 * and V0162 turn it on for.
 *
 * <p>Every probe here runs as a login role holding nothing but {@code
 * horecaos_application} — the same shape {@code DatabasePrivilegeTests} uses,
 * and for the same reason: a row-level-security claim tested from the
 * migrator's connection proves nothing, because PostgreSQL exempts a table's
 * owner from its own policies automatically. The property that matters is
 * what {@code horecaos_app} sees, and only a non-owner connection can show it.
 *
 * <p>Four things this suite has to show, and none of them follow from the
 * others:
 *
 * <ol>
 *   <li>a query that forgets its tenant predicate sees nothing of another
 *       tenant's row once a tenant is bound, and still sees its own;
 *   <li>a tenant bound on one pooled connection cannot still be in effect on
 *       the next, unrelated transaction that borrows the same connection;
 *   <li>the one legitimate cross-tenant path this schema has —
 *       {@code InventoryService.expireStaleReservations} — still reaches
 *       every tenant's expired holds, through the exempt role, not through a
 *       forgotten predicate;
 *   <li>and the catalog agrees: every table V0162 names carries row-level
 *       security, enabled and force-free, through exactly one policy.
 * </ol>
 */
class RowLevelSecurityBackstopTests {

    private static final String APP_PROBE = "rls_probe_app";
    private static final String APP_PROBE_PASSWORD = "rls-probe-app";

    private static final List<String> RLS_PROTECTED_INVENTORY_TABLES = List.of(
            "inventory.stock_items",
            "inventory.positions",
            "inventory.movements",
            "inventory.reservations",
            "inventory.reservation_lines");

    private static TestDatabase.Handle db;
    private static DataSource asOwner;
    private static DataSource asApplication;
    private static JdbcClient owner;
    private static JdbcClient application;
    private static TransactionTemplate appTx;
    private static TenantRlsSession rls;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the row-level security probes");
        db = TestDatabase.migrated();

        asOwner = db.dataSource();
        owner = JdbcClient.create(asOwner);

        createLoginRole(APP_PROBE, APP_PROBE_PASSWORD, TestDatabase.APPLICATION_ROLE);

        // TestDatabase's container never runs
        // infra/production/postgres-init/10-application-role.sh -- that script
        // exists specifically because horecaos_app cannot be named from inside a
        // Flyway migration (see V0161's own comment) and so no migration grants
        // it horecaos_platform_bypass either. Restated here so this probe holds
        // the same shape the real horecaos_app role holds in every environment
        // that does run the script.
        owner.sql("GRANT horecaos_platform_bypass TO " + APP_PROBE + " WITH INHERIT FALSE")
                .update();

        asApplication = db.dataSourceAs(APP_PROBE, APP_PROBE_PASSWORD);
        application = JdbcClient.create(asApplication);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(asApplication));
        rls = new JdbcTenantRlsSession(application);
    }

    @AfterAll
    static void stopDatabase() {
        if (db == null) {
            return;
        }
        db.close();
        try {
            TestDatabase.onCluster("DROP ROLE IF EXISTS " + APP_PROBE);
        } catch (RuntimeException leftover) {
            System.err.println("RowLevelSecurityBackstopTests: " + APP_PROBE + " outlived the suite ("
                    + leftover.getMessage() + ")");
        }
    }

    // -----------------------------------------------------------------------
    // The property that matters: a missing predicate is not a leak
    // -----------------------------------------------------------------------

    /**
     * The exact query ADR 0056 is written for: no {@code WHERE tenant_id = ?}
     * at all, driven on purpose here instead of waiting for a future change to
     * omit one by accident.
     */
    @Test
    @DisplayName("a query with no tenant predicate sees only the bound tenant's row, in both directions")
    void aTenantBlindQuerySeesOnlyTheBoundTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID stockItemA = seedStockItem(tenantA);
        UUID stockItemB = seedStockItem(tenantB);

        Optional<UUID> nothingBound = appTx.execute(status -> tenantBlindLookup(stockItemA));
        assertThat(nothingBound)
                .as("no tenant bound must mean no row visible, not every row -- the fail-closed direction")
                .isEmpty();

        Optional<UUID> ownRow = appTx.execute(status -> {
            rls.bindTenant(tenantA);
            return tenantBlindLookup(stockItemA);
        });
        assertThat(ownRow)
                .as("bound to its own tenant, the tenant-blind query still finds the row")
                .contains(stockItemA);

        Optional<UUID> othersRow = appTx.execute(status -> {
            rls.bindTenant(tenantA);
            return tenantBlindLookup(stockItemB);
        });
        assertThat(othersRow)
                .as("bound to tenant A, the identical query must not find tenant B's row by id alone")
                .isEmpty();

        Optional<UUID> theOtherTenantsOwnRow = appTx.execute(status -> {
            rls.bindTenant(tenantB);
            return tenantBlindLookup(stockItemB);
        });
        assertThat(theOtherTenantsOwnRow)
                .as("and tenant B, once bound, sees exactly its own row back")
                .contains(stockItemB);
    }

    /** The write side of the same policy: {@code WITH CHECK} defaults to {@code USING}. */
    @Test
    @DisplayName("a reservation cannot be inserted for a tenant other than the one bound")
    void aReservationCannotBeWrittenForAnUnboundTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        Fixture fixtureA = seedTenantBrandLocationVariant(tenantA);
        Fixture fixtureB = seedTenantBrandLocationVariant(tenantB);

        // Bound to A, an insert honestly addressed to B's tenant_id is refused --
        // not merely invisible afterward, which INSERT under RLS cannot be.
        Boolean crossTenantInsertSucceeded = appTx.execute(status -> {
            rls.bindTenant(tenantA);
            try {
                new JdbcInventoryStore(application)
                        .insertReservation(
                                UUID.randomUUID(),
                                tenantB,
                                fixtureB.brandId(),
                                fixtureB.locationId(),
                                "QUOTE",
                                UUID.randomUUID(),
                                Instant.parse("2026-09-05T01:00:00Z"),
                                Instant.parse("2026-09-05T00:00:00Z"));
                return Boolean.TRUE;
            } catch (DataAccessException refused) {
                return Boolean.FALSE;
            }
        });
        assertThat(crossTenantInsertSucceeded)
                .as("row-level security's WITH CHECK defaults to its USING clause, so a mismatched "
                        + "tenant_id is refused on write, not merely hidden on the next read")
                .isFalse();

        // The matching, honest insert for the bound tenant succeeds.
        UUID reservationId = UUID.randomUUID();
        Boolean created = appTx.execute(status -> {
            rls.bindTenant(tenantA);
            return new JdbcInventoryStore(application)
                    .insertReservation(
                            reservationId,
                            tenantA,
                            fixtureA.brandId(),
                            fixtureA.locationId(),
                            "QUOTE",
                            UUID.randomUUID(),
                            Instant.parse("2026-09-05T01:00:00Z"),
                            Instant.parse("2026-09-05T00:00:00Z"));
        });
        assertThat(created).isTrue();
        assertThat(owner.sql("SELECT tenant_id FROM inventory.reservations WHERE id = :id")
                        .param("id", reservationId)
                        .query(UUID.class)
                        .single())
                .isEqualTo(tenantA);
    }

    // -----------------------------------------------------------------------
    // The pool cannot leak a tenant between transactions
    // -----------------------------------------------------------------------

    /**
     * A pool of exactly one connection, so the second transaction below is not
     * merely likely to reuse the first's physical connection — it has no other
     * connection it could possibly get.
     */
    @Test
    @DisplayName(
            "a tenant bound in one transaction does not survive into the next transaction on the same pooled connection")
    void theBoundTenantDoesNotLeakAcrossPooledTransactions() {
        UUID tenantA = UUID.randomUUID();
        UUID stockItemA = seedStockItem(tenantA);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.jdbcUrl());
        config.setUsername(APP_PROBE);
        config.setPassword(APP_PROBE_PASSWORD);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("rls-leak-probe");

        try (HikariDataSource singleConnectionPool = new HikariDataSource(config)) {
            JdbcClient pooledClient = JdbcClient.create(singleConnectionPool);
            TenantRlsSession pooledRls = new JdbcTenantRlsSession(pooledClient);
            TransactionTemplate pooledTx =
                    new TransactionTemplate(new DataSourceTransactionManager(singleConnectionPool));

            Optional<UUID> seenWhileBound = pooledTx.execute(status -> {
                pooledRls.bindTenant(tenantA);
                return pooledClient
                        .sql("SELECT id FROM inventory.stock_items WHERE id = :id")
                        .param("id", stockItemA)
                        .query(UUID.class)
                        .optional();
            });
            assertThat(seenWhileBound)
                    .as("bound, the row is visible on this connection")
                    .contains(stockItemA);

            // A brand new transaction. On a one-connection pool it can only be
            // handed the exact physical connection the transaction above just
            // committed and returned. Nothing here binds a tenant at all.
            Optional<UUID> seenAfterReturn = pooledTx.execute(status -> pooledClient
                    .sql("SELECT id FROM inventory.stock_items WHERE id = :id")
                    .param("id", stockItemA)
                    .query(UUID.class)
                    .optional());
            assertThat(seenAfterReturn)
                    .as("the previous transaction's tenant must not still be bound on this connection -- "
                            + "SET LOCAL has to have reverted at COMMIT, or this is a leak worse than no "
                            + "row-level security at all")
                    .isEmpty();

            // Not necessarily SQL NULL: PostgreSQL creates a custom setting's
            // placeholder the first time anything SETs it, and reverting a
            // SET LOCAL at COMMIT restores whatever the session-level value
            // was beforehand -- for a placeholder nothing has ever SET at
            // session scope, that is the empty string, not "unset". That is
            // exactly why V0161's policy template reads
            // NULLIF(current_setting(...), '')::uuid rather than the setting
            // directly: an empty string is the ordinary, expected shape of
            // "reverted", not an edge case the policy merely tolerates.
            String settingAfterReturn = pooledTx.execute(status -> pooledClient
                    .sql("SELECT current_setting('horecaos.tenant_id', true)")
                    .query(String.class)
                    .single());
            assertThat(settingAfterReturn)
                    .as("and the setting itself must read back as unbound -- null or empty, either of "
                            + "which the policy's NULLIF(...,'') treats identically as no tenant bound")
                    .isNullOrEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Background work keeps working, through the exempt role
    // -----------------------------------------------------------------------

    /**
     * {@code InventoryService.expireStaleReservations} is inventory's one
     * cross-tenant statement, driven here through the real service class over
     * the real application-role connection -- not a raw SQL stand-in -- to
     * show the whole path, {@link TenantRlsSession#bindPlatform()} included,
     * survives V0162 rather than merely compiling against it.
     */
    @Test
    @DisplayName("the cross-tenant reservation sweep still reaches every tenant once row-level security is on")
    void theCrossTenantSweepStillReachesEveryTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        Fixture fixtureA = seedTenantBrandLocationVariant(tenantA);
        Fixture fixtureB = seedTenantBrandLocationVariant(tenantB);

        Instant expired = Instant.parse("2026-09-04T00:00:00Z");
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        JdbcInventoryStore ownerStore = new JdbcInventoryStore(owner);
        ownerStore.insertReservation(
                UUID.randomUUID(),
                tenantA,
                fixtureA.brandId(),
                fixtureA.locationId(),
                "QUOTE",
                UUID.randomUUID(),
                expired,
                expired.minusSeconds(60));
        ownerStore.insertReservation(
                UUID.randomUUID(),
                tenantB,
                fixtureB.brandId(),
                fixtureB.locationId(),
                "QUOTE",
                UUID.randomUUID(),
                expired,
                expired.minusSeconds(60));

        InventoryService inventory = new InventoryService(
                new JdbcInventoryStore(application), event -> {}, Clock.fixed(now, ZoneOffset.UTC), fact -> {}, rls);

        Integer expiredCount = appTx.execute(status -> inventory.expireStaleReservations());
        assertThat(expiredCount)
                .as("horecaos_platform_bypass must let the sweep see both tenants' holds in one pass, "
                        + "the same as before row-level security existed")
                .isEqualTo(2);

        assertThat(owner.sql("SELECT count(*) FROM inventory.reservations WHERE status = 'EXPIRED'")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    /**
     * The end-to-end path an operator actually drives, over the application
     * role, proving the ADR 0056 retrofit changed nothing about ordinary,
     * single-tenant behaviour.
     */
    @Test
    @DisplayName("listing a variant and toggling its availability still works end to end under the application role")
    void ordinaryAvailabilityStillWorksUnderTheApplicationRole() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = seedTenantBrandLocationVariant(tenantId);

        InventoryService inventory = new InventoryService(
                new JdbcInventoryStore(application),
                event -> {},
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC),
                fact -> {},
                rls);

        appTx.execute(status -> inventory.listVariantAtLocation(
                tenantId, fixture.brandId(), fixture.locationId(), fixture.variantId(), TrackingMode.BINARY));

        Boolean availableAtStart = appTx.execute(status -> inventory
                .checkAvailability(tenantId, fixture.locationId(), Set.of(fixture.variantId()))
                .available());
        assertThat(availableAtStart)
                .as("a freshly listed binary item starts available")
                .isTrue();

        appTx.executeWithoutResult(status -> inventory.setAvailability(
                tenantId, fixture.locationId(), fixture.variantId(), false, "SOLD_OUT", null));

        Boolean availableAfterToggle = appTx.execute(status -> inventory
                .checkAvailability(tenantId, fixture.locationId(), Set.of(fixture.variantId()))
                .available());
        assertThat(availableAfterToggle)
                .as("and the 86 toggle still takes effect")
                .isFalse();
    }

    /**
     * The narrowing the brief for this ADR asks for by name: {@code
     * BYPASSRLS} is all-or-nothing once held, so what stops it being ambient
     * is that almost nothing holds it. A role that is a perfectly ordinary
     * member of {@code horecaos_application} -- everything {@code
     * horecaos_app} itself is, apart from the one extra grant V0161 gives it
     * -- must not be able to assume {@code horecaos_platform_bypass} merely
     * by asking.
     */
    @Test
    @DisplayName("an ordinary application-role connection cannot assume the bypass role without being granted it")
    void theBypassRoleIsNotAmbient() {
        String ordinaryProbe = "rls_probe_ordinary";
        String ordinaryPassword = "rls-probe-ordinary";
        createLoginRole(ordinaryProbe, ordinaryPassword, TestDatabase.APPLICATION_ROLE);
        HikariDataSource ordinary = (HikariDataSource) db.dataSourceAs(ordinaryProbe, ordinaryPassword);
        try {
            JdbcClient ordinaryClient = JdbcClient.create(ordinary);
            TransactionTemplate ordinaryTx = new TransactionTemplate(new DataSourceTransactionManager(ordinary));

            assertThat((Boolean) ordinaryTx.execute(status -> {
                        try {
                            ordinaryClient
                                    .sql("SET LOCAL ROLE horecaos_platform_bypass")
                                    .update();
                            return true;
                        } catch (DataAccessException refused) {
                            return false;
                        }
                    }))
                    .as("membership in horecaos_application alone must not carry the bypass role -- only "
                            + "horecaos_app itself is granted it, and WITH INHERIT FALSE at that (V0161)")
                    .isFalse();
        } finally {
            // Closed before the DROP, same reason TestDatabase.Handle.close()
            // closes its own pools before dropping the database: a role dropped
            // out from under a pool that still believes it holds a connection is
            // how the next test in this class would inherit a wall of Hikari
            // errors instead of a clean failure.
            ordinary.close();
            TestDatabase.onCluster("DROP ROLE IF EXISTS " + ordinaryProbe);
        }
    }

    // -----------------------------------------------------------------------
    // The catalog agrees with what V0162 claims
    // -----------------------------------------------------------------------

    /**
     * The same genre as {@code TenantScopedReferenceCatalogTests}: asked of
     * PostgreSQL, not of the migration source, so a policy dropped by a later
     * "fix" or an {@code ALTER TABLE ... DISABLE ROW LEVEL SECURITY} typed by
     * hand on a server fails here rather than nowhere.
     *
     * <p>This list may only grow. Shrinking it means a table that used to be
     * covered no longer is, which is the one direction ADR 0056 exists to
     * make impossible without somebody noticing.
     */
    @Test
    @DisplayName("V0162: every inventory table has row-level security enabled through exactly one policy")
    void everyInventoryTableCarriesExactlyOnePolicy() {
        for (String table : RLS_PROTECTED_INVENTORY_TABLES) {
            Map<String, Object> relation =
                    owner.sql("""
                    SELECT c.relrowsecurity, c.relforcerowsecurity
                      FROM pg_catalog.pg_class c
                      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname || '.' || c.relname = :table
                    """).param("table", table).query().singleRow();

            assertThat(relation)
                    .as("%s must have row-level security enabled (V0162)", table)
                    .containsEntry("relrowsecurity", true);
            assertThat(relation)
                    .as(
                            "%s must not FORCE row-level security -- horecaos_migrator owns it and must "
                                    + "stay exempt as owner, which FORCE would take away",
                            table)
                    .containsEntry("relforcerowsecurity", false);

            List<Map<String, Object>> policies =
                    owner.sql("""
                    SELECT polname, pg_catalog.pg_get_expr(polqual, polrelid) AS using_clause
                      FROM pg_catalog.pg_policy
                     WHERE polrelid = :table::regclass
                    """).param("table", table).query().listOfRows();

            assertThat(policies)
                    .as("%s must carry exactly one row-level security policy", table)
                    .hasSize(1);
            assertThat((String) policies.get(0).get("using_clause"))
                    .as("%s's policy must read the ADR 0056 session setting", table)
                    .contains("tenant_id")
                    .contains("horecaos.tenant_id");
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private Optional<UUID> tenantBlindLookup(UUID stockItemId) {
        // Deliberately no tenant predicate -- see the class-level comment.
        return application
                .sql("SELECT id FROM inventory.stock_items WHERE id = :id")
                .param("id", stockItemId)
                .query(UUID.class)
                .optional();
    }

    private record Fixture(UUID brandId, UUID locationId, UUID variantId) {}

    /** A stock item, and everything its foreign keys require, for one fresh tenant. */
    private static UUID seedStockItem(UUID tenantId) {
        Fixture fixture = seedTenantBrandLocationVariant(tenantId);
        return new JdbcInventoryStore(owner)
                .createStockItem(
                        tenantId,
                        fixture.brandId(),
                        fixture.locationId(),
                        fixture.variantId(),
                        TrackingMode.UNTRACKED,
                        Instant.parse("2026-09-05T00:00:00Z"));
    }

    /**
     * The tenant/brand/location/product/variant chain {@code inventory.*}'s
     * foreign keys require, seeded through the owner connection -- which owns
     * every table involved and so is unaffected by row-level security
     * regardless of which schema it is later turned on for, exactly like
     * every fixture builder elsewhere in this suite.
     */
    private static Fixture seedTenantBrandLocationVariant(UUID tenantId) {
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);

        owner.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status)
                VALUES (:id, :slug, 'RLS probe', 'RLS probe', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                """).param("id", tenantId).param("slug", "rls-" + suffix).update();

        owner.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'BRAND', 'brand', 'Brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();

        owner.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :tenantId, :brandId, 'LOC', 'loc', 'Location', 'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        owner.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code)
                VALUES (:id, :tenantId, :brandId, 'SKU')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        owner.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id)
                VALUES (:id, :tenantId, :brandId, :productId)
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();

        return new Fixture(brandId, locationId, variantId);
    }

    private static void createLoginRole(String name, String password, String groupRole) {
        owner.sql("DROP ROLE IF EXISTS " + name).update();
        owner.sql("CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "'").update();
        owner.sql("ALTER ROLE " + name + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + groupRole + " TO " + name).update();
    }
}
