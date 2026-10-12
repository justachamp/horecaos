package uz.horecaos.platform.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uz.horecaos.platform.printing.PrintingWorld.BAR;
import static uz.horecaos.platform.printing.PrintingWorld.BRAND;
import static uz.horecaos.platform.printing.PrintingWorld.GRILL;
import static uz.horecaos.platform.printing.PrintingWorld.LOCATION;
import static uz.horecaos.platform.printing.PrintingWorld.OTHER_TENANT;
import static uz.horecaos.platform.printing.PrintingWorld.SIBLING;
import static uz.horecaos.platform.printing.PrintingWorld.SIBLINGS_STATION;
import static uz.horecaos.platform.printing.PrintingWorld.TENANT;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.rls.JdbcTenantRlsSession;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.provider.telegram.TelegramEventClass;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;

/**
 * The shape ADR 0154 gives the printing tables, proven on the migrated schema (V0520 to V0523): what the
 * database refuses so a service bug cannot write it, each refusal beside a negative control that shows the
 * same statement succeeding when it is well formed -- a refusal tested without the control only proves the
 * insert was malformed.
 *
 * <p>The row-level-security probe connects as a login role that holds nothing but the application role,
 * because the migrator owns the tables and PostgreSQL exempts an owner from its own policies.
 */
class PrintingMigrationTests {

    private static final String APP_PROBE = "printing_probe_app";
    private static final String APP_PROBE_PASSWORD = "printing-probe-app";

    private static final List<String> PRINTING_TABLES = List.of(
            "printing.printers",
            "printing.print_routes",
            "printing.receipt_profiles",
            "printing.print_jobs",
            "printing.agent_printer_adverts");

    private static final String DIGEST = "a".repeat(64);

    private static TestDatabase.Handle db;
    private static JdbcClient owner;
    private static PrintingWorld world;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the printing schema tests");
        db = TestDatabase.migrated();
        owner = JdbcClient.create(db.dataSource());
        world = new PrintingWorld(owner, TestProtection.envelope());
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
            System.err.println(
                    "PrintingMigrationTests: " + APP_PROBE + " outlived the suite (" + leftover.getMessage() + ")");
        }
    }

    @BeforeEach
    void freshWorld() {
        world.reset();
        // A device's grant names a role row; the platform roles are rows a running application writes at start,
        // and resetting the tenants takes them with it.
        new RoleRegistrySynchronizer(owner).synchronize();
    }

    // ----------------------------------------------------------------- printers

    @Test
    @DisplayName("a printer names an agent of its own location: another location's agent is refused, its own is not")
    void aPrinterCannotBindToAnotherLocationsAgent() {
        UUID here = device(LOCATION, "PRINT_AGENT");
        UUID elsewhere = device(SIBLING, "PRINT_AGENT");

        insertPrinter(LOCATION, "Counter", 80, "AGENT", here, "counter");

        assertRefused(() -> insertPrinter(LOCATION, "Bar", 80, "AGENT", elsewhere, "bar"), "fk_printer_agent_device");
    }

    @Test
    @DisplayName("an AGENT printer names both agent and alias, a BROWSER printer names neither")
    void aPrinterIsEitherAnAgentsOrTheBrowsers() {
        UUID agent = device(LOCATION, "PRINT_AGENT");

        insertPrinter(LOCATION, "Browser", 58, "BROWSER", null, null);
        insertPrinter(LOCATION, "Agent", 80, "AGENT", agent, "agent-1");

        assertRefused(
                () -> insertPrinter(LOCATION, "No alias", 80, "AGENT", agent, null), "ck_printer_connection_pair");
        assertRefused(() -> insertPrinter(LOCATION, "No agent", 80, "AGENT", null, "x"), "ck_printer_connection_pair");
        assertRefused(
                () -> insertPrinter(LOCATION, "Browser with agent", 80, "BROWSER", agent, null),
                "ck_printer_connection_pair");
        assertRefused(() -> insertPrinter(LOCATION, "Wide", 100, "BROWSER", null, null), "ck_printer_tape_width");
    }

    @Test
    @DisplayName("one active alias belongs to one printer; a retired printer frees it")
    void anAliasIsNotSharedByTwoActivePrinters() {
        UUID agent = device(LOCATION, "PRINT_AGENT");
        UUID first = insertPrinter(LOCATION, "First", 80, "AGENT", agent, "same");

        assertRefused(() -> insertPrinter(LOCATION, "Second", 80, "AGENT", agent, "same"), "uq_printer_agent_alias");

        owner.sql("UPDATE printing.printers SET status = 'RETIRED' WHERE id = :id")
                .param("id", first)
                .update();
        insertPrinter(LOCATION, "Third", 80, "AGENT", agent, "same");
    }

    // ------------------------------------------------------------------- routes

    @Test
    @DisplayName("a route names a printer of its own location")
    void aRouteCannotNameAnotherLocationsPrinter() {
        UUID mine = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);
        UUID theirs = insertPrinter(SIBLING, "Counter", 80, "BROWSER", null, null);

        insertRoute(LOCATION, "ORDER_SLIP", null, mine, 1);

        assertRefused(() -> insertRoute(LOCATION, "ORDER_SLIP", null, theirs, 1), "fk_print_route_printer");
    }

    @Test
    @DisplayName("a station route is a kitchen ticket route on a station of its own location")
    void aStationRouteIsAKitchenTicketRouteOfItsOwnLocation() {
        UUID printer = insertPrinter(LOCATION, "Kitchen", 80, "BROWSER", null, null);

        insertRoute(LOCATION, "KITCHEN_TICKET", GRILL, printer, 1);

        assertRefused(() -> insertRoute(LOCATION, "ORDER_SLIP", BAR, printer, 1), "ck_print_route_station");
        assertRefused(
                () -> insertRoute(LOCATION, "KITCHEN_TICKET", SIBLINGS_STATION, printer, 1), "fk_print_route_station");
    }

    @Test
    @DisplayName("a route prints one to three copies, and one route exists per (type, station, printer)")
    void aRouteHasBoundedCopiesAndNoDuplicate() {
        UUID printer = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);

        insertRoute(LOCATION, "ORDER_SLIP", null, printer, 3);

        assertRefused(() -> insertRoute(LOCATION, "ORDER_SLIP", null, printer, 1), "uq_print_route_target");
        assertRefused(() -> insertRoute(LOCATION, "FISCAL_RECEIPT", null, printer, 4), "ck_print_route_copies");
        assertRefused(() -> insertRoute(LOCATION, "FISCAL_RECEIPT", null, printer, 0), "ck_print_route_copies");
        assertRefused(() -> insertRoute(LOCATION, "TEST_PAGE", null, printer, 1), "ck_print_route_type");
        insertRoute(LOCATION, "FISCAL_RECEIPT", null, printer, 3);
    }

    // ----------------------------------------------------------------- profiles

    @Test
    @DisplayName("a footer is at most three lines of at most 48 characters per locale")
    void aFooterIsBounded() {
        insertProfile(BRAND, "{\"ru\": [\"Спасибо\", \"Приходите ещё\", \"Wi-Fi: horeca\"]}");

        assertRefused(
                () -> owner.sql("UPDATE printing.receipt_profiles SET footer_texts = :f::jsonb WHERE brand_id = :b")
                        .param("f", "{\"ru\": [\"a\", \"b\", \"c\", \"d\"]}")
                        .param("b", BRAND)
                        .update(),
                "ck_receipt_profile_footer");
        assertRefused(
                () -> owner.sql("UPDATE printing.receipt_profiles SET footer_texts = :f::jsonb WHERE brand_id = :b")
                        .param("f", "{\"ru\": [\"" + "x".repeat(49) + "\"]}")
                        .param("b", BRAND)
                        .update(),
                "ck_receipt_profile_footer");
        assertRefused(
                () -> owner.sql("UPDATE printing.receipt_profiles SET footer_texts = :f::jsonb WHERE brand_id = :b")
                        .param("f", "{\"ru\": \"one string\"}")
                        .param("b", BRAND)
                        .update(),
                "ck_receipt_profile_footer");
        assertRefused(
                () -> owner.sql("UPDATE printing.receipt_profiles SET footer_texts = :f::jsonb WHERE brand_id = :b")
                        .param("f", "[\"not an object\"]")
                        .param("b", BRAND)
                        .update(),
                "ck_receipt_profile_footer");
        owner.sql("UPDATE printing.receipt_profiles SET footer_texts = :f::jsonb WHERE brand_id = :b")
                .param("f", "{\"ru\": [\"" + "x".repeat(48) + "\"]}")
                .param("b", BRAND)
                .update();
    }

    // --------------------------------------------------------------------- jobs

    @Test
    @DisplayName(
            "an automatic job exists once per (printer, source, revision, station, copy); a reprint is not automatic")
    void anAutomaticJobIsUnique() {
        UUID printer = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);
        UUID source = UUID.randomUUID();

        insertJob(printer, "ORDER_SLIP", "ORDER", source, 0, "AUTO", null, "QUEUED");

        assertRefused(
                () -> insertJob(printer, "ORDER_SLIP", "ORDER", source, 0, "AUTO", null, "QUEUED"),
                "uq_print_job_auto_source");
        // The controls: a later revision and a manual reprint are each a different job.
        insertJob(printer, "ORDER_SLIP", "ORDER", source, 1, "AUTO", null, "QUEUED");
        insertJob(printer, "ORDER_SLIP", "ORDER", source, 0, "MANUAL", "waiter", "QUEUED");
        insertJob(printer, "ORDER_SLIP", "ORDER", source, 0, "MANUAL", "waiter", "QUEUED");
    }

    @Test
    @DisplayName("a document type and its source go together, and a test page has no source")
    void aJobsSourceMatchesItsType() {
        UUID printer = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);

        insertJob(printer, "TEST_PAGE", "NONE", null, 0, "MANUAL", "waiter", "QUEUED");

        assertRefused(
                () -> insertJob(printer, "TEST_PAGE", "ORDER", UUID.randomUUID(), 0, "MANUAL", "waiter", "QUEUED"),
                "ck_print_job_source_pair");
        assertRefused(
                () -> insertJob(printer, "ORDER_SLIP", "NONE", null, 0, "AUTO", null, "QUEUED"),
                "ck_print_job_source_pair");
        assertRefused(
                () -> insertJob(printer, "FISCAL_RECEIPT", "ORDER", UUID.randomUUID(), 0, "AUTO", null, "QUEUED"),
                "ck_print_job_source_pair");
    }

    @Test
    @DisplayName("a manual job names who and why, an automatic job names neither")
    void aManualJobNamesItsRequester() {
        UUID printer = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);

        assertRefused(
                () -> insertJob(printer, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "MANUAL", null, "QUEUED"),
                "ck_print_job_origin_actor");
        assertRefused(
                () -> insertJob(printer, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "AUTO", "waiter", "QUEUED"),
                "ck_print_job_origin_actor");
        insertJob(printer, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "MANUAL", "waiter", "QUEUED");
    }

    @Test
    @DisplayName("a claimed job holds a lease and names the agent; a lease has a token and an expiry together")
    void aClaimedJobHoldsALease() {
        UUID agent = device(LOCATION, "PRINT_AGENT");
        UUID foreignAgent = device(SIBLING, "PRINT_AGENT");
        UUID printer = insertPrinter(LOCATION, "Counter", 80, "AGENT", agent, "counter");
        UUID job = insertJob(printer, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "AUTO", null, "QUEUED");

        assertRefused(
                () -> owner.sql("UPDATE printing.print_jobs SET status = 'CLAIMED' WHERE id = :id")
                        .param("id", job)
                        .update(),
                "ck_print_job_claimed");
        assertRefused(
                () -> owner.sql("UPDATE printing.print_jobs SET lease_token = :t WHERE id = :id")
                        .param("t", UUID.randomUUID())
                        .param("id", job)
                        .update(),
                "ck_print_job_lease_pair");
        assertRefused(() -> claim(job, foreignAgent), "fk_print_job_claimed_by");
        claim(job, agent);
        assertThat(owner.sql("SELECT status FROM printing.print_jobs WHERE id = :id")
                        .param("id", job)
                        .query(String.class)
                        .single())
                .isEqualTo("CLAIMED");
    }

    @Test
    @DisplayName("six attempts at most, a known status and failure code, and a digest that is a SHA-256")
    void aJobsLifecycleIsBounded() {
        UUID printer = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);
        UUID job = insertJob(printer, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "AUTO", null, "QUEUED");

        assertRefused(() -> setColumn(job, "attempt", "7"), "ck_print_job_attempt");
        assertRefused(() -> setColumn(job, "status", "'LOST'"), "ck_print_job_status");
        assertRefused(() -> setColumn(job, "failure_code", "'ON_FIRE'"), "ck_print_job_failure");
        assertRefused(() -> setColumn(job, "document_digest", "'not-a-digest'"), "ck_print_job_digest");
        assertRefused(() -> setColumn(job, "status", "'PRINTED'"), "ck_print_job_printed");
        setColumn(job, "attempt", "6");
        setColumn(job, "failure_code", "'TRANSPORT'");
    }

    @Test
    @DisplayName("a job names a printer, route and station of its own location, and a reprint names an existing job")
    void aJobStaysInItsLocation() {
        UUID mine = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);
        UUID theirs = insertPrinter(SIBLING, "Counter", 80, "BROWSER", null, null);
        UUID theirRoute = insertRoute(SIBLING, "ORDER_SLIP", null, theirs, 1);

        assertRefused(() -> insertJobRaw(theirs, null, null, UUID.randomUUID()), "fk_print_job_printer");
        assertRefused(() -> insertJobRaw(mine, theirRoute, null, UUID.randomUUID()), "fk_print_job_route");
        assertRefused(() -> insertJobRaw(mine, null, SIBLINGS_STATION, UUID.randomUUID()), "fk_print_job_station");
        UUID reprint = insertJob(mine, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "MANUAL", "waiter", "QUEUED");
        UUID original = insertJob(mine, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "AUTO", null, "QUEUED");
        assertRefused(
                () -> owner.sql("UPDATE printing.print_jobs SET reprint_of = :other WHERE id = :id")
                        .param("other", UUID.randomUUID())
                        .param("id", reprint)
                        .update(),
                "fk_print_job_reprint_of");
        owner.sql("UPDATE printing.print_jobs SET reprint_of = :other WHERE id = :id")
                .param("other", original)
                .param("id", reprint)
                .update();
    }

    // ---------------------------------------------- the neighbours this build changes

    @Test
    @DisplayName("a device principal may be a PRINT_AGENT, and may not be a class nobody built")
    void theDeviceClassCheckAdmitsAPrintAgent() {
        device(LOCATION, "KITCHEN_KDS");
        device(LOCATION, "KITCHEN_VDU");
        device(LOCATION, "PRINT_AGENT");

        assertRefused(() -> device(LOCATION, "COFFEE_MACHINE"), "ck_device_principal_class");
    }

    @Test
    @DisplayName("a kitchen ticket counts its amendments and cannot count below zero")
    void aTicketCountsItsAmendments() {
        assertThat(owner.sql("""
                        SELECT column_default FROM information_schema.columns
                         WHERE table_schema = 'kitchen' AND table_name = 'tickets'
                           AND column_name = 'amendment_revision'
                        """).query(String.class).single()).isEqualTo("0");
        assertThat(owner.sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                         WHERE conname = 'ck_ticket_amendment_revision'
                        """).query(String.class).single()).contains("amendment_revision >= 0");
    }

    /**
     * Both directions: a value the enum can write that the CHECK refuses fails a real binding at run time,
     * and a value the CHECK admits that no enum constant names is a class nobody can ever send.
     */
    @Test
    @DisplayName("the Telegram event-class CHECK admits exactly the classes the enum names")
    void theTelegramCheckAndTheEnumAgree() {
        String definition = owner.sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                         WHERE conname = 'ck_telegram_binding_event_class'
                        """).query(String.class).single();
        Set<String> admitted = quoted(definition);

        Set<String> named =
                Arrays.stream(TelegramEventClass.values()).map(Enum::name).collect(Collectors.toSet());

        assertThat(admitted).as("what the CHECK admits").isEqualTo(named);
        assertThat(admitted).contains("PRINTER_OFFLINE", "PRINT_JOB_DEAD");
    }

    // --------------------------------------------------- the tenant boundary

    @Test
    @DisplayName("every printing table carries one tenant-isolation policy, enabled")
    void everyPrintingTableIsUnderRowLevelSecurity() {
        for (String table : PRINTING_TABLES) {
            String[] parts = table.split("\\.");
            Optional<Boolean> enabled = owner.sql("""
                            SELECT c.relrowsecurity FROM pg_catalog.pg_class c
                              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                             WHERE n.nspname = :schema AND c.relname = :name
                            """)
                    .param("schema", parts[0])
                    .param("name", parts[1])
                    .query(Boolean.class)
                    .optional();
            assertThat(enabled).as(table + " exists").isPresent();
            assertThat(enabled.get())
                    .as(table + " has row-level security enabled")
                    .isTrue();
            List<String> policies =
                    owner.sql("""
                            SELECT polname FROM pg_catalog.pg_policy
                             WHERE polrelid = (:table)::regclass
                            """).param("table", table).query(String.class).list();
            assertThat(policies).as(table + " policies").containsExactly("tenant_isolation");
        }
    }

    @Test
    @DisplayName("as the application role, a tenant-blind query sees only the bound tenant's jobs")
    void aTenantBlindQuerySeesOnlyTheBoundTenant() {
        TestDatabase.Handle handle = db;
        owner.sql("DROP ROLE IF EXISTS " + APP_PROBE).update();
        owner.sql("CREATE ROLE " + APP_PROBE + " LOGIN PASSWORD '" + APP_PROBE_PASSWORD + "'")
                .update();
        owner.sql("ALTER ROLE " + APP_PROBE + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + APP_PROBE).update();
        DataSource asApplication = handle.dataSourceAs(APP_PROBE, APP_PROBE_PASSWORD);
        JdbcClient application = JdbcClient.create(asApplication);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(asApplication));
        TenantRlsSession rls = new JdbcTenantRlsSession(application);

        UUID printer = insertPrinter(LOCATION, "Counter", 80, "BROWSER", null, null);
        UUID job = insertJob(printer, "ORDER_SLIP", "ORDER", UUID.randomUUID(), 0, "AUTO", null, "QUEUED");

        Optional<UUID> unbound = tx.execute(status -> application
                .sql("SELECT id FROM printing.print_jobs WHERE id = :id")
                .param("id", job)
                .query(UUID.class)
                .optional());
        assertThat(unbound).as("no tenant bound means no row, not every row").isEmpty();

        Optional<UUID> own = tx.execute(status -> {
            rls.bindTenant(TENANT);
            return application
                    .sql("SELECT id FROM printing.print_jobs WHERE id = :id")
                    .param("id", job)
                    .query(UUID.class)
                    .optional();
        });
        assertThat(own).as("the bound tenant finds its own job").contains(job);

        Optional<UUID> foreign = tx.execute(status -> {
            rls.bindTenant(OTHER_TENANT);
            return application
                    .sql("SELECT id FROM printing.print_jobs WHERE id = :id")
                    .param("id", job)
                    .query(UUID.class)
                    .optional();
        });
        assertThat(foreign).as("another tenant cannot find it by id alone").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRefused(ThrowingStatement statement, String constraint) {
        assertThatThrownBy(statement::run)
                .as("refused by " + constraint)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraint);
    }

    @FunctionalInterface
    private interface ThrowingStatement {
        void run();
    }

    private static Set<String> quoted(String definition) {
        Matcher matcher = Pattern.compile("'([A-Z0-9_]+)'").matcher(definition);
        Set<String> found = new java.util.TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** A device principal written the way the kitchen's own HTTP suite writes one: grant first, then the row. */
    private UUID device(UUID location, String deviceClass) {
        UUID id = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        String subject = "device-" + id;
        owner.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                             status, granted_by, reason, valid_from)
                        VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                                'ACTIVE', 'test-fixture', 'printing schema test', :validFrom)
                        """)
                .param("id", grantId)
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PRINT_AGENT))
                .param("locationId", location)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
        owner.sql("""
                        INSERT INTO iam.device_principals
                            (id, tenant_id, brand_id, location_id, device_class, display_name,
                             keycloak_client_internal_id, keycloak_client_id, principal_subject,
                             grant_id, status, enrolled_by)
                        VALUES (:id, :tenantId, :brandId, :locationId, :deviceClass, :displayName,
                                :kcInternal, :kcClientId, :subject, :grantId, 'ACTIVE', 'test-fixture')
                        """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("deviceClass", deviceClass)
                .param("displayName", deviceClass + " " + id)
                .param("kcInternal", "kc-internal-" + id)
                .param("kcClientId", "kc-client-" + id)
                .param("subject", subject)
                .param("grantId", grantId)
                .update();
        return id;
    }

    private UUID insertPrinter(
            UUID location,
            String name,
            int width,
            String kind,
            @org.jspecify.annotations.Nullable UUID agent,
            @org.jspecify.annotations.Nullable String alias) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO printing.printers
                            (id, tenant_id, brand_id, location_id, display_name, tape_width_mm, agent_device_id,
                             agent_printer_key, connection_kind)
                        VALUES (:id, :tenantId, :brandId, :locationId, :name, :width, :agent, :alias, :kind)
                        """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("name", name)
                .param("width", width)
                .param("agent", agent)
                .param("alias", alias)
                .param("kind", kind)
                .update();
        return id;
    }

    private UUID insertRoute(
            UUID location, String type, @org.jspecify.annotations.Nullable UUID station, UUID printer, int copies) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO printing.print_routes
                            (id, tenant_id, brand_id, location_id, document_type, station_id, printer_id, copies)
                        VALUES (:id, :tenantId, :brandId, :locationId, :type, :station, :printer, :copies)
                        """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("type", type)
                .param("station", station)
                .param("printer", printer)
                .param("copies", copies)
                .update();
        return id;
    }

    private void insertProfile(UUID brand, String footer) {
        owner.sql("""
                        INSERT INTO printing.receipt_profiles (tenant_id, brand_id, footer_texts, updated_by)
                        VALUES (:tenantId, :brandId, :footer::jsonb, 'test-fixture')
                        """)
                .param("tenantId", TENANT)
                .param("brandId", brand)
                .param("footer", footer)
                .update();
    }

    private UUID insertJob(
            UUID printer,
            String type,
            String sourceType,
            @org.jspecify.annotations.Nullable UUID source,
            int revision,
            String origin,
            @org.jspecify.annotations.Nullable String requestedBy,
            String status) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-08T07:00:00Z");
        owner.sql("""
                        INSERT INTO printing.print_jobs
                            (id, tenant_id, brand_id, location_id, printer_id, document_type, source_type,
                             source_id, source_revision, origin, requested_by, reason_code, status,
                             next_attempt_at, expires_at, locale, tape_width_mm, document_digest,
                             payload_purge_after, queued_at)
                        VALUES (:id, :tenantId, :brandId, :locationId, :printer, :type, :sourceType, :source,
                                :revision, :origin, :requestedBy, :reason, :status, :now, :later, 'ru', 80,
                                :digest, :later, :now)
                        """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("printer", printer)
                .param("type", type)
                .param("sourceType", sourceType)
                .param("source", source)
                .param("revision", revision)
                .param("origin", origin)
                .param("requestedBy", requestedBy)
                .param("reason", "MANUAL".equals(origin) ? "CUSTOMER_REQUEST" : null)
                .param("status", status)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("later", now.plus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC))
                .param("digest", DIGEST)
                .update();
        return id;
    }

    /** A job whose printer, route and station are chosen freely, so a test can mismatch them. */
    private UUID insertJobRaw(
            UUID printer,
            @org.jspecify.annotations.Nullable UUID route,
            @org.jspecify.annotations.Nullable UUID station,
            UUID source) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-08T07:00:00Z");
        owner.sql("""
                        INSERT INTO printing.print_jobs
                            (id, tenant_id, brand_id, location_id, printer_id, route_id, document_type, source_type,
                             source_id, station_id, origin, status, next_attempt_at, expires_at, locale,
                             tape_width_mm, document_digest, payload_purge_after, queued_at)
                        VALUES (:id, :tenantId, :brandId, :locationId, :printer, :route, 'KITCHEN_TICKET',
                                'KITCHEN_TICKET', :source, :station, 'AUTO', 'QUEUED', :now, :later, 'ru', 80,
                                :digest, :later, :now)
                        """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("printer", printer)
                .param("route", route)
                .param("source", source)
                .param("station", station)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("later", now.plus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC))
                .param("digest", DIGEST)
                .update();
        return id;
    }

    private void claim(UUID job, UUID agent) {
        owner.sql("""
                        UPDATE printing.print_jobs
                           SET status = 'CLAIMED', lease_token = :token, lease_expires_at = now() + interval '60 seconds',
                               claimed_by_device_id = :agent
                         WHERE id = :id
                        """)
                .param("token", UUID.randomUUID())
                .param("agent", agent)
                .param("id", job)
                .update();
    }

    private void setColumn(UUID job, String column, String literal) {
        owner.sql("UPDATE printing.print_jobs SET " + column + " = " + literal + " WHERE id = :id")
                .param("id", job)
                .update();
    }
}
