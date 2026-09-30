package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0127's self-service end rests on one fact about a tenant's module: which
 * door it came through. Two things guard it that the endpoint tests do not
 * reach: the V0442 column and its backfill, and the rule that names the last
 * statement month a module still bills once it ends.
 */
class TenantModuleAcquisitionTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final UUID TENANT = UUID.fromString("018f9d50-4000-7000-8000-0000000000c1");
    private static final UUID MODULE = UUID.fromString("018f9d50-4000-7000-8000-0000000000c2");
    private static final String SELF_SERVICE_REASON = "Purchased from the operations console";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;

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
        jdbc.sql("TRUNCATE TABLE commercial.tenant_modules, commercial.modules").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'acquisition', 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO commercial.modules (id, code, name, billing_unit, currency, unit_price_minor,
                    status, created_by)
                VALUES (:id, 'kds', 'Kitchen display', 'PER_TENANT', 'UZS', 150000, 'DRAFT', 'author')
                """).param("id", MODULE).update();
    }

    // ------------------------------------------------------------ the column

    @Test
    void aRowWrittenWithoutSayingHowItWasAcquiredIsPlatformAssigned() {
        UUID id = insertHeld("sold with the pilot", null);

        assertThat(acquiredVia(id))
                .as("the safe side: an application that predates the column cannot make a row the tenant may end")
                .isEqualTo("PLATFORM");
    }

    @Test
    void theColumnRefusesAnyDoorButTheTwoNamedOnes() {
        UUID id = insertHeld("sold with the pilot", null);

        assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.tenant_modules SET acquired_via = 'TENANT_ADMIN' "
                                + "WHERE id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------- the backfill

    @Test
    void theBackfillFilesTheRowsTheTenantDoorWroteAsSelfServiceAndNoOthers() throws IOException {
        UUID bought = insertHeld(SELF_SERVICE_REASON, null);
        UUID assigned = insertHeld("sold with the pilot", null);
        UUID alreadyMarked = insertHeld("typed by staff", "SELF_SERVICE");

        String backfill = backfillStatement();
        jdbc.sql(backfill).update();

        assertThat(acquiredVia(bought))
                .as("the one fixed reason only the tenant door has ever written")
                .isEqualTo("SELF_SERVICE");
        assertThat(acquiredVia(assigned))
                .as("a typed reason is HorecaOS staff giving the module")
                .isEqualTo("PLATFORM");
        assertThat(acquiredVia(alreadyMarked))
                .as("the backfill only ever moves rows towards SELF_SERVICE")
                .isEqualTo("SELF_SERVICE");

        jdbc.sql(backfill).update();
        assertThat(acquiredVia(bought)).as("re-running changes nothing").isEqualTo("SELF_SERVICE");
        assertThat(acquiredVia(assigned)).isEqualTo("PLATFORM");
    }

    // ------------------------------------------- the last month that still bills

    @Test
    void aModuleEndedMidMonthBillsThatMonthInFull() {
        assertThat(lastBilled(BillingUnit.PER_TENANT, "2026-09-03T05:00:00Z", "2026-09-20T10:00:00Z"))
                .isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void aModuleEndedTheSecondItsMonthBeganDoesNotBillThatMonth() {
        // 2026-09-30T19:00:00Z is 2026-10-01T00:00 in Tashkent. A statement bills a
        // module that ended after the month began (StatementService via JdbcModuleStore
        // .overlapping: ended_at > start), so ending on the dot leaves October unbilled.
        assertThat(lastBilled(BillingUnit.PER_TENANT, "2026-08-10T05:00:00Z", "2026-09-30T19:00:00Z"))
                .isEqualTo(YearMonth.of(2026, 9));
        assertThat(lastBilled(BillingUnit.PER_TENANT, "2026-08-10T05:00:00Z", "2026-09-30T19:00:00.000001Z"))
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void theMonthRollsInTheTenantsTimezoneNotUtc() {
        // 20:00Z on the 30th is already 01:00 on the 1st in Tashkent.
        assertThat(lastBilled(BillingUnit.PER_TENANT, "2026-08-10T05:00:00Z", "2026-09-30T20:00:00Z"))
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void aOneOffModuleBillsOnlyTheMonthItStartedInWheneverItEnds() {
        assertThat(lastBilled(BillingUnit.ONE_OFF, "2026-08-15T05:00:00Z", "2026-10-05T10:00:00Z"))
                .isEqualTo(YearMonth.of(2026, 8));
    }

    @Test
    void aModuleEndedTheInstantItStartedStillNamesTheMonthItStartedIn() {
        assertThat(lastBilled(BillingUnit.PER_UNIT, "2026-09-11T09:00:00Z", "2026-09-11T09:00:00Z"))
                .isEqualTo(YearMonth.of(2026, 9));
    }

    // -------------------------------------------------------------------- util

    private static YearMonth lastBilled(BillingUnit unit, String startedAt, String endedAt) {
        return ModuleCatalogService.lastBilledMonth(unit, Instant.parse(startedAt), Instant.parse(endedAt), TASHKENT);
    }

    private UUID insertHeld(String startReason, @Nullable String acquiredVia) {
        UUID id = UUID.randomUUID();
        // A row shaped like every row before V0442: acquired_via is left to its default
        // unless a case needs it set.
        jdbc.sql("""
                INSERT INTO commercial.tenant_modules (id, tenant_id, module_id, started_at, started_by, start_reason)
                VALUES (:id, :tenantId, :moduleId, now() - interval '3 days', 'someone', :reason)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("moduleId", MODULE)
                .param("reason", startReason)
                .update();
        if (acquiredVia != null) {
            jdbc.sql("UPDATE commercial.tenant_modules SET acquired_via = :via WHERE id = :id")
                    .param("via", acquiredVia)
                    .param("id", id)
                    .update();
        }
        // The one-live-instance index: end it so the next row of the test can be live too.
        jdbc.sql("UPDATE commercial.tenant_modules SET ended_at = now(), ended_by = 'x', end_reason = 'x' "
                        + "WHERE id = :id")
                .param("id", id)
                .update();
        return id;
    }

    private String acquiredVia(UUID id) {
        return jdbc.sql("SELECT acquired_via FROM commercial.tenant_modules WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    /** The migration's own UPDATE, so the test cannot drift from what ships. */
    private static String backfillStatement() throws IOException {
        try (InputStream migration = TenantModuleAcquisitionTests.class
                .getClassLoader()
                .getResourceAsStream("db/migration/V0442__tenant_module_records_how_it_was_acquired.sql")) {
            assertThat(migration).as("V0442 is on the classpath").isNotNull();
            String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
            Matcher update = Pattern.compile("(?s)UPDATE commercial\\.tenant_modules.*?;")
                    .matcher(sql);
            assertThat(update.find()).as("V0442 backfills existing rows").isTrue();
            String statement = update.group();
            return statement.substring(0, statement.length() - 1);
        }
    }
}
