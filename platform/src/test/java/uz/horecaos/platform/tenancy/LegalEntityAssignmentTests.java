package uz.horecaos.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.application.LegalEntityService;
import uz.horecaos.platform.tenancy.application.LegalEntityService.AssignLocationCommand;
import uz.horecaos.platform.tenancy.application.LegalEntityService.RegisterLegalEntityCommand;
import uz.horecaos.platform.tenancy.application.TenantResourceConflictException;
import uz.horecaos.platform.tenancy.domain.LegalEntity;
import uz.horecaos.platform.tenancy.domain.TaxpayerNumber;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcLegalEntityStore;

/**
 * ADR 0038's legal entities and the effective-dated assignment of a branch to
 * one, against PostgreSQL.
 *
 * <p>Every case here is a way a receipt comes to name the wrong taxpayer. Two
 * assignments covering one day, so the resolver picks by row order. A resolution
 * on today's date rather than the order's, so a branch that changed hands last
 * week restates who sold last week's orders. An entity id that belongs to another
 * tenant. And a branch with no assignment at all, which must answer "nobody"
 * rather than answer with a default.
 *
 * <p>The two tables are Flyway's, from V0053: what matters here is that the
 * constraints being asserted are real PostgreSQL constraints and not Java checks
 * dressed up as them. They used to be recreated by hand for every test in this
 * class through a fixture — {@code LegalEntitySchema}, since deleted — written
 * before V0053 existed and never retired once it did; {@code TestDatabase
 * .migrated()} already applies it, so the redundant drop-and-recreate is gone
 * and the {@code TRUNCATE ... CASCADE} below is what resets the tables now.
 */
class LegalEntityAssignmentTests {

    private static final UUID TENANT = UUID.fromString("018f7a10-1000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f7a10-1000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f7a10-1000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f7a10-1000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018f7a10-1000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f7a10-1000-7000-8000-0000000000c2");

    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcLegalEntityStore store;
    private LegalEntityService service;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenancy();

        store = new JdbcLegalEntityStore(jdbc);
        service = new LegalEntityService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ----------------------------------------------------------- registration

    @Test
    @DisplayName("a company is registered in DRAFT and cannot sell until somebody activates it")
    void aNewEntityCannotSellUntilActivated() {
        LegalEntity entity = register("OSHXONA", "123456789");

        assertThat(entity.canSell())
                .as("a company that can be named as a seller the instant somebody types an INN "
                        + "will be named as one before finance has checked the INN")
                .isFalse();

        service.activate(TENANT, entity.id().value(), 1);
        assertThat(service.require(TENANT, entity.id().value()).canSell()).isTrue();
    }

    @Test
    @DisplayName("two companies in one tenant cannot share an INN")
    void oneTaxpayerNumberPerTenant() {
        register("FIRST", "123456789");

        assertThatThrownBy(() -> register("SECOND", "123456789"))
                .as("two rows for one taxpayer make every per-entity binding ambiguous")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("taxpayer number");
    }

    @Test
    @DisplayName("a taxpayer number that is not nine digits is refused before it reaches a receipt")
    void aTaxpayerNumberIsNineDigits() {
        assertThatThrownBy(() -> new TaxpayerNumber("12345678")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxpayerNumber("30512345678901"))
                .as("fourteen digits is a PINFL, which identifies a person and not a company")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unregistered company cannot hold a VAT certificate reference")
    void vatRegistrationAndItsCertificateMoveTogether() {
        LegalEntity entity = register("OSHXONA", "123456789");

        assertThatThrownBy(() -> entity.applyVatRegistration(false, "VAT-2026-1"))
                .as("an entity that deregisters and keeps its certificate reads as registered, "
                        + "and every receipt it issues charges VAT it does not owe")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- assignment

    @Test
    @DisplayName("two assignments covering one day are refused by the database, not by a service")
    void overlappingAssignmentsAreImpossible() {
        UUID first = activated("FIRST", "123456789");
        UUID second = activated("SECOND", "223456789");

        insertAssignment(LOCATION, first, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> insertAssignment(LOCATION, second, LocalDate.of(2026, 8, 1), null))
                .as("two overlapping assignments mean two INNs are simultaneously correct and "
                        + "the resolver picks by row order")
                .hasMessageContaining("ex_location_fiscal_assignment_no_overlap");
    }

    @Test
    @DisplayName("assigning a branch closes its predecessor on the successor's first day")
    void assignmentIsCloseThenOpenWithNoGapAndNoOverlap() {
        UUID first = activated("FIRST", "123456789");
        UUID second = activated("SECOND", "223456789");

        service.assign(
                TENANT,
                new AssignLocationCommand(
                        BRAND, LOCATION, first, LocalDate.of(2026, 1, 1), "finance@example.test", "board-2026-01"));
        service.assign(
                TENANT,
                new AssignLocationCommand(
                        BRAND, LOCATION, second, LocalDate.of(2026, 9, 1), "finance@example.test", "board-2026-09"));

        // 31 August belongs to the first company, 1 September to the second, and no
        // day belongs to both or to neither.
        assertThat(sellerOn(LocalDate.of(2026, 8, 31)).orElseThrow().legalEntityId())
                .isEqualTo(first);
        assertThat(sellerOn(LocalDate.of(2026, 9, 1)).orElseThrow().legalEntityId())
                .isEqualTo(second);
        assertThat(service.assignmentHistory(TENANT, LOCATION)).hasSize(2);
    }

    @Test
    @DisplayName("a receipt resolves the company in force on its own business date, not today's")
    void aReRegistrationDoesNotRewriteWhoSoldAPastOrder() {
        UUID first = activated("FIRST", "123456789");
        UUID second = activated("SECOND", "223456789");

        service.assign(
                TENANT,
                new AssignLocationCommand(
                        BRAND, LOCATION, first, LocalDate.of(2026, 1, 1), "finance@example.test", null));
        service.assign(
                TENANT,
                new AssignLocationCommand(
                        BRAND, LOCATION, second, LocalDate.of(2026, 8, 20), "finance@example.test", null));

        FiscalSeller onTheOrdersDate = sellerOn(LocalDate.of(2026, 7, 4)).orElseThrow();

        assertThat(onTheOrdersDate.legalEntityId())
                .as("a re-registration must not restate who issued a receipt that is already "
                        + "with the tax authority")
                .isEqualTo(first);
        assertThat(onTheOrdersDate.taxpayerNumber()).isEqualTo("123456789");
    }

    @Test
    @DisplayName("a branch with no assignment on that date resolves nobody, and never a default")
    void anUnassignedBranchResolvesNobody() {
        UUID entity = activated("FIRST", "123456789");
        service.assign(
                TENANT,
                new AssignLocationCommand(
                        BRAND, LOCATION, entity, LocalDate.of(2026, 8, 1), "finance@example.test", null));

        assertThat(sellerOn(LocalDate.of(2026, 7, 31)))
                .as("a receipt that cannot name a seller must not be issued under whichever "
                        + "company the tenant happens to hold")
                .isEmpty();
        assertThat(store.sellerFor(TENANT, OTHER_LOCATION, LocalDate.of(2026, 8, 24)))
                .isEmpty();
    }

    @Test
    @DisplayName("another tenant's location never resolves this tenant's company")
    void resolutionIsScopedToTheTenantAndTheLocationTogether() {
        UUID entity = activated("FIRST", "123456789");
        service.assign(
                TENANT,
                new AssignLocationCommand(
                        BRAND, LOCATION, entity, LocalDate.of(2026, 1, 1), "finance@example.test", null));

        assertThat(store.sellerFor(OTHER_TENANT, LOCATION, LocalDate.of(2026, 8, 24)))
                .as("a location id arriving from another module's row is not evidence of "
                        + "anything; the predicate carries the tenant beside it")
                .isEmpty();
    }

    @Test
    @DisplayName("an entity belonging to another tenant cannot be assigned to this one's branch")
    void anEntityCannotBeBorrowedAcrossTenants() {
        UUID foreign = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'FOREIGN', 'Another company', '999999999', 'ACTIVE')
                """).param("id", foreign).param("t", OTHER_TENANT).update();

        assertThatThrownBy(() -> service.assign(
                        TENANT,
                        new AssignLocationCommand(
                                BRAND, LOCATION, foreign, LocalDate.of(2026, 1, 1), "finance@example.test", null)))
                .as("the tenant predicate is on the read as well as on the constraint")
                .isInstanceOf(RuntimeException.class);

        assertThat(sellerOn(LocalDate.of(2026, 8, 24))).isEmpty();
    }

    @Test
    @DisplayName("a suspended company cannot be named as the seller at a branch")
    void aSuspendedEntityCannotBeAssigned() {
        UUID entity = activated("FIRST", "123456789");
        service.suspend(TENANT, entity, 2);

        assertThatThrownBy(() -> service.assign(
                        TENANT,
                        new AssignLocationCommand(
                                BRAND, LOCATION, entity, LocalDate.of(2026, 1, 1), "finance@example.test", null)))
                .isInstanceOf(TenantResourceConflictException.class)
                .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("a company that already sells at another branch may also sell at this one")
    void oneCompanyMayHoldSeveralBranches() {
        UUID entity = activated("FIRST", "123456789");

        service.assign(
                TENANT,
                new AssignLocationCommand(
                        BRAND, LOCATION, entity, LocalDate.of(2026, 1, 1), "finance@example.test", null));
        service.assign(
                TENANT,
                new AssignLocationCommand(
                        OTHER_BRAND, OTHER_LOCATION, entity, LocalDate.of(2026, 1, 1), "finance@example.test", null));

        assertThat(sellerOn(LocalDate.of(2026, 8, 24)).orElseThrow().legalEntityId())
                .isEqualTo(entity);
        assertThat(store.sellerFor(TENANT, OTHER_LOCATION, LocalDate.of(2026, 8, 24)))
                .as("the exclusion constraint is per location, not per company: one company "
                        + "routinely runs every branch a tenant has")
                .isPresent();
    }

    // -------------------------------------------------------------- fixtures

    private Optional<FiscalSeller> sellerOn(LocalDate businessDate) {
        return store.sellerFor(TENANT, LOCATION, businessDate);
    }

    private LegalEntity register(String code, String tin) {
        return service.register(
                TENANT,
                new RegisterLegalEntityCommand(
                        code, code + " MCHJ", code, tin, false, null, null, "Tashkent", "+998901234567"));
    }

    private UUID activated(String code, String tin) {
        LegalEntity entity = register(code, tin);
        service.activate(TENANT, entity.id().value(), 1);
        return entity.id().value();
    }

    /** Straight to SQL, because what is being asserted is the constraint and not the service. */
    private void insertAssignment(UUID locationId, UUID entityId, LocalDate from, @Nullable LocalDate until) {
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id,
                    location_id, legal_entity_id, effective_from, effective_until, approved_by)
                VALUES (:id, :t, :b, :loc, :e, :from, :until, 'finance@example.test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", locationId.equals(LOCATION) ? BRAND : OTHER_BRAND)
                .param("loc", locationId)
                .param("e", entityId)
                .param("from", from)
                .param("until", until)
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'entities-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'other-tenant', 'Other', 'Other', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'SECOND', 'second', 'Second brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'YUN', 'yunusobod', 'Yunusobod', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_LOCATION)
                .param("t", TENANT)
                .param("b", OTHER_BRAND)
                .update();
    }
}
