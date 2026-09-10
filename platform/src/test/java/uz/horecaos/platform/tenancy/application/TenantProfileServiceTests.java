package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.domain.BusinessType;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantProfileStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantProfileStore.PublicHoliday;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0090 against PostgreSQL: a change of country waits for a second
 * signature under the policy the migration seeds, and moves the tenant once.
 */
class TenantProfileServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2100-7000-8000-0000000000c1");
    private static final ActorRef MAKER = ActorRef.user("support-1", null);
    private static final ActorRef CHECKER = ActorRef.user("support-2", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private JdbcApprovalService approvals;
    private TenantProfileService profiles;

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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // Not TRUNCATE tenant.tenants CASCADE: that would cascade into
        // audit.approval_policies and take the policy V0203 seeds with it,
        // and whether that policy is there is part of what this suite proves.
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :id")
                .param("id", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.public_holidays WHERE created_by <> 'migration V0203'")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'pilot', 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        Clock clock = Clock.fixed(Instant.parse("2026-09-11T09:00:00Z"), ZoneOffset.UTC);
        JdbcAuditRecorder audit =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        approvals = new JdbcApprovalService(jdbc, audit, clock, new SimpleMeterRegistry());
        profiles = new TenantProfileService(new JdbcTenantProfileStore(jdbc), approvals, audit, clock);
    }

    @Test
    void anExistingTenantTradesInUzbekistanAsARestaurant() {
        assertThat(profiles.all()).singleElement().satisfies(profile -> {
            assertThat(profile.countryCode()).isEqualTo("UZ");
            assertThat(profile.businessType()).isEqualTo(BusinessType.RESTAURANT);
        });
    }

    @Test
    void aChangeOfCountryWaitsForASecondSignatureAndMovesTheTenantOnce() {
        TenantProfileService.CountryChange first =
                transactions.execute(status -> profiles.changeCountry(TENANT, "KZ", MAKER, "opening in Almaty"));

        assertThat(first.status()).isEqualTo(TenantProfileService.CountryChange.AWAITING_APPROVAL);
        assertThat(country()).as("nothing moves on one signature").isEqualTo("UZ");

        UUID requestId = java.util.Objects.requireNonNull(first.approvalRequestId());
        transactions.executeWithoutResult(status ->
                approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked the contract"));

        TenantProfileService.CountryChange second =
                transactions.execute(status -> profiles.changeCountry(TENANT, "KZ", MAKER, "opening in Almaty"));

        assertThat(second.status()).isEqualTo(TenantProfileService.CountryChange.CHANGED);
        assertThat(country()).isEqualTo("KZ");
        assertThat(jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(String.class)
                        .single())
                .as("the signature is spent by the change it authorised")
                .isEqualTo("CONSUMED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'tenant.country.changed'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void aCountryThePlatformDoesNotTradeInIsRefusedAndTheSameCountryIsNoChange() {
        assertThatThrownBy(() -> transactions.execute(status -> profiles.changeCountry(TENANT, "FR", MAKER, "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not trade in FR");
        assertThat(transactions
                        .execute(status -> profiles.changeCountry(TENANT, "UZ", MAKER, "x"))
                        .status())
                .isEqualTo(TenantProfileService.CountryChange.UNCHANGED);
    }

    @Test
    void aBusinessTypeIsRecordedWithoutASecondSignature() {
        transactions.executeWithoutResult(
                status -> profiles.setBusinessType(TENANT, BusinessType.DARK_KITCHEN, MAKER, "delivery only"));

        assertThat(profiles.all().getFirst().businessType()).isEqualTo(BusinessType.DARK_KITCHEN);
    }

    @Test
    void uzbekistansFixedHolidaysAreSeededAndAMovableOneIsEnteredForItsYear() {
        assertThat(profiles.holidays())
                .filteredOn(holiday -> holiday.countryCode().equals("UZ"))
                .extracting(PublicHoliday::name)
                .contains("Navruz", "Independence Day");

        UUID hayit = transactions.execute(status ->
                profiles.addHoliday("UZ", "Ramazon Hayit", null, null, LocalDate.of(2027, 3, 10), MAKER, "announced"));
        assertThatThrownBy(() -> transactions.execute(
                        status -> profiles.addHoliday("UZ", "Navruz again", 3, 21, null, MAKER, "dup")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already a holiday");
        assertThatThrownBy(() -> transactions.execute(
                        status -> profiles.addHoliday("UZ", "No such day", 2, 30, null, MAKER, "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no such day");
        assertThatThrownBy(() -> transactions.execute(
                        status -> profiles.addHoliday("UZ", "Both", 1, 2, LocalDate.of(2027, 1, 2), MAKER, "x")))
                .isInstanceOf(ApiException.class);

        transactions.executeWithoutResult(
                status -> profiles.removeHoliday(java.util.Objects.requireNonNull(hayit), MAKER, "moved"));
        List<String> names =
                profiles.holidays().stream().map(PublicHoliday::name).toList();
        assertThat(names).doesNotContain("Ramazon Hayit");
    }

    private String country() {
        return jdbc.sql("SELECT country_code FROM tenant.tenants WHERE id = :id")
                .param("id", TENANT)
                .query(String.class)
                .single();
    }
}
