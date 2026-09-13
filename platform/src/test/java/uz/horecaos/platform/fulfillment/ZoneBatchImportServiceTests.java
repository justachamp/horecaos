package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fulfillment.application.RegionService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.application.ZoneBatchImportService;
import uz.horecaos.platform.fulfillment.application.ZoneBatchImportService.BatchImportReport;
import uz.horecaos.platform.fulfillment.application.ZoneBatchImportService.ImportRow;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore.RegionGeography;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Bulk geozone upload (operations gap map row {@code 3.6c}, ADR 0037).
 *
 * <p>The property under test is the one ADR 0037 names as the reason this row
 * exists: a coordinate-order mistake produces a geometrically valid polygon
 * that lands nowhere near where it should, and no containment test catches it
 * — only comparing against a bounding box or a map does. So the tests here are
 * against a real PostgreSQL and its PostGIS functions, not a mock that would
 * happily agree any coordinates are fine.
 */
class ZoneBatchImportServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOON = Instant.parse("2026-09-12T07:00:00Z");

    /** A small triangle in central Tashkent: (lon, lat) pairs, as GeoJSON requires. */
    private static final String TASHKENT_TRIANGLE = """
            {"type":"Polygon","coordinates":[[[69.20,41.30],[69.25,41.30],[69.225,41.35],[69.20,41.30]]]}""";

    /**
     * The exact same three points as {@link #TASHKENT_TRIANGLE}, with every
     * (lon, lat) pair transposed to (lat, lon) — the mistake ADR 0037 names.
     * Still a perfectly valid, non-self-intersecting ring; PostGIS has no
     * opinion about which axis is which. It lands near 41°E, 69°N — the
     * Norwegian Sea, not Tashkent — which is exactly why nothing but a region
     * bounding box or a map catches it.
     */
    private static final String SWAPPED_TRIANGLE = """
            {"type":"Polygon","coordinates":[[[41.30,69.20],[41.30,69.25],[41.35,69.225],[41.30,69.20]]]}""";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcServiceZoneStore zoneStore;
    private ServiceZoneService zones;
    private ZoneBatchImportService batchImport;
    private UUID regionId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for zone batch import tests");
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
        jdbc.sql("TRUNCATE TABLE fulfillment.zone_location_bindings, fulfillment.service_zone_versions, "
                        + "fulfillment.service_zones, fulfillment.regions CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        RecordingAudit audit = new RecordingAudit();
        CurrentActor actor = () -> new AuthenticatedActor(ACTOR.toString(), Set.of(), Map.of());
        var mapper = JsonMapper.builder().build();

        zoneStore = new JdbcServiceZoneStore(jdbc);
        zones = new ServiceZoneService(zoneStore, mapper, clock, audit, actor);

        TransactionTemplate unitOfWork = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        batchImport = new ZoneBatchImportService(zones, zoneStore, unitOfWork);

        seedTenancy();
        regionId = new RegionService(new JdbcRegionStore(jdbc), clock, audit, actor)
                .create(
                        TENANT,
                        new RegionGeography(
                                "TASHKENT", "Ташкент", "Toshkent", "Tashkent", 41.31, 69.24, 40.5, 68.5, 42.0, 70.0));
    }

    @Test
    @DisplayName("every accepted row lands as a DRAFT version, never activated, whether or not it is a dry run")
    void everyAcceptedRowLandsAsDraftAndOnlyAsDraft() {
        BatchImportReport report =
                batchImport.importBatch(TENANT, BRAND, List.of(row("legacy-1", "IMPORT-1", TASHKENT_TRIANGLE)), false);

        assertThat(report.accepted()).isEqualTo(1);
        assertThat(report.rows().get(0).zoneId()).isNotNull();

        assertThat(jdbc.sql("SELECT status FROM fulfillment.service_zone_versions WHERE zone_id = :zoneId")
                        .param("zoneId", report.rows().get(0).zoneId())
                        .query(String.class)
                        .list())
                .as("the batch endpoint never calls activate -- that stays behind DELIVERY_ZONE_ACTIVATE and X.4's map")
                .containsExactly("DRAFT");
    }

    @Test
    @DisplayName("a dry run reports the same outcome as a real import and then leaves no row behind")
    void aDryRunPersistsNothing() {
        List<ImportRow> rows = List.of(row("legacy-1", "DRYRUN-1", TASHKENT_TRIANGLE));

        BatchImportReport dryRun = batchImport.importBatch(TENANT, BRAND, rows, true);
        assertThat(dryRun.dryRun()).isTrue();
        assertThat(dryRun.accepted()).isEqualTo(1);
        assertThat(dryRun.rows().get(0).zoneId()).isNotNull();

        assertThat(zones.listZones(TENANT, BRAND))
                .as("the dry run computed an outcome by actually inserting and rolling back -- nothing survives")
                .isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.service_zone_versions")
                        .query(Long.class)
                        .single())
                .isZero();

        BatchImportReport committed = batchImport.importBatch(TENANT, BRAND, rows, false);
        assertThat(committed.accepted()).isEqualTo(1);
        assertThat(zones.listZones(TENANT, BRAND)).hasSize(1);
    }

    @Test
    @DisplayName(
            "a row outside the region's bounding box is accepted as DRAFT but carries the coordinate-order warning")
    void aHemisphereFlippedRowIsFlaggedNotSilentlyAccepted() {
        BatchImportReport report = batchImport.importBatch(
                TENANT, BRAND, List.of(row("legacy-swapped", "SWAPPED-1", SWAPPED_TRIANGLE)), false);

        assertThat(report.accepted()).isEqualTo(1);
        var outcome = report.rows().get(0);
        assertThat(outcome.warnings())
                .as("the shape is a valid polygon -- ST_IsValid never objects -- so only the region "
                        + "bounding box catches a coordinate transposition")
                .anyMatch(warning -> warning.startsWith("OUTSIDE_REGION_BBOX"));

        // And the well-formed triangle in the same batch carries no such warning,
        // so the check is discriminating rather than firing on every row.
        BatchImportReport control =
                batchImport.importBatch(TENANT, BRAND, List.of(row("legacy-good", "GOOD-1", TASHKENT_TRIANGLE)), false);
        assertThat(control.rows().get(0).warnings()).isEmpty();
    }

    @Test
    @DisplayName("one row's failure does not poison the rest of the batch, and the report says which row failed")
    void oneBadRowDoesNotSinkTheBatch() {
        // Seed a zone whose code the batch will collide with.
        zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "TAKEN", "existing", "existing", "existing");

        BatchImportReport report = batchImport.importBatch(
                TENANT,
                BRAND,
                List.of(
                        row("legacy-collide", "TAKEN", TASHKENT_TRIANGLE),
                        row("legacy-ok", "FRESH-1", TASHKENT_TRIANGLE)),
                false);

        assertThat(report.totalRows()).isEqualTo(2);
        assertThat(report.accepted()).isEqualTo(1);
        assertThat(report.rejected()).isEqualTo(1);

        var collided = report.rows().get(0);
        assertThat(collided.externalRef()).isEqualTo("legacy-collide");
        assertThat(collided.accepted()).isFalse();
        assertThat(collided.error()).isNotNull();

        var ok = report.rows().get(1);
        assertThat(ok.externalRef()).isEqualTo("legacy-ok");
        assertThat(ok.accepted()).isTrue();

        // The good row actually committed despite its neighbour's failure --
        // proof the two ran in independent transactions, not one shared one.
        assertThat(zones.listZones(TENANT, BRAND))
                .as("the pre-existing TAKEN zone plus the one fresh row that succeeded")
                .hasSize(2);
    }

    @Test
    @DisplayName(
            "dryRun catches two rows sharing a code the same way a real import would, not the false-accepted pair a per-row rollback hides")
    void dryRunCatchesAnIntraBatchDuplicateCode() {
        // T17 adversarial-review finding: each row's own PROPAGATION_REQUIRES_NEW
        // transaction is rolled back under dryRun before the next row runs, so
        // without an in-memory check, two rows sharing a code would each insert
        // against a database that has never seen the other and both come back
        // accepted -- misreporting what committing for real would actually do
        // (the second insert would trip uq_service_zone_code, exactly like
        // oneBadRowDoesNotSinkTheBatch's pre-seeded collision above).
        BatchImportReport dryRun = batchImport.importBatch(
                TENANT,
                BRAND,
                List.of(
                        row("legacy-first", "DUP-1", TASHKENT_TRIANGLE),
                        row("legacy-second", "DUP-1", TASHKENT_TRIANGLE)),
                true);

        assertThat(dryRun.totalRows()).isEqualTo(2);
        assertThat(dryRun.accepted())
                .as("only the first occurrence of a duplicated code may be accepted")
                .isEqualTo(1);
        assertThat(dryRun.rejected()).isEqualTo(1);

        var first = dryRun.rows().get(0);
        assertThat(first.externalRef()).isEqualTo("legacy-first");
        assertThat(first.accepted()).isTrue();

        var second = dryRun.rows().get(1);
        assertThat(second.externalRef()).isEqualTo("legacy-second");
        assertThat(second.accepted()).isFalse();
        assertThat(second.error()).isNotNull();
        assertThat(second.error()).contains("DUPLICATE_CODE_IN_BATCH");

        // dryRun's own promise: nothing committed either way.
        assertThat(zones.listZones(TENANT, BRAND)).isEmpty();

        // The same batch, for real, must fail the very same way: the second row
        // is caught before ever reaching the database, not merely discovered by
        // the unique constraint after wasting the round trip.
        BatchImportReport real = batchImport.importBatch(
                TENANT,
                BRAND,
                List.of(
                        row("legacy-first", "DUP-2", TASHKENT_TRIANGLE),
                        row("legacy-second", "DUP-2", TASHKENT_TRIANGLE)),
                false);
        assertThat(real.accepted()).isEqualTo(1);
        assertThat(real.rejected()).isEqualTo(1);
        assertThat(real.rows().get(1).error()).contains("DUPLICATE_CODE_IN_BATCH");
        assertThat(zones.listZones(TENANT, BRAND))
                .as("only the one non-duplicate row from this call committed")
                .hasSize(1);
    }

    // ------------------------------------------------------------------- fixtures

    private ImportRow row(String externalRef, String code, String geoJson) {
        return new ImportRow(
                externalRef,
                ZoneRole.DELIVERY,
                code,
                code + " RU",
                code + " UZ",
                code + " EN",
                regionId,
                0,
                "UZS",
                null,
                null,
                null,
                geoJson,
                ACTOR);
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'zone-import-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private static final class RecordingAudit implements AuditRecorder {

        @Override
        public void record(AuditFact fact) {
            // Not asserted on here; ZoneLifecycleAndRegionTests already covers the
            // ADR 0027 audit trail this delegates to.
        }
    }
}
