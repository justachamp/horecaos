package uz.horecaos.platform.customers.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0094: a phone number is looked up in every tenant with its own key, and the lookup is audited without it. */
class PlatformCustomerLookupControllerTests {

    private static final UUID FIRST = UUID.fromString("018f6f4e-2100-7000-8000-0000000000f2");
    private static final UUID SECOND = UUID.fromString("018f6f4e-2100-7000-8000-0000000000f3");
    private static final String PHONE = "+998901234567";

    private static TestDatabase.Handle db;

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

    @Test
    void everyTenantIsAskedAndTheAuditNamesTheReasonButNeverTheNumber() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        tenant(jdbc, FIRST, "first", "Non uyi");
        tenant(jdbc, SECOND, "second", "Osh markazi");
        UUID account = UUID.randomUUID();
        CustomerProfileService profiles = mock(CustomerProfileService.class);
        when(profiles.findAccountsByContact(any(), eq(CustomerProfileService.ContactType.PHONE), eq(PHONE)))
                .thenReturn(List.of());
        when(profiles.findAccountsByContact(eq(SECOND), eq(CustomerProfileService.ContactType.PHONE), eq(PHONE)))
                .thenReturn(List.of(account));
        List<AuditFact> facts = new ArrayList<>();
        PlatformCustomerLookupController controller = new PlatformCustomerLookupController(
                profiles,
                jdbc,
                facts::add,
                () -> new AuthenticatedActor("support-1", Set.of("platform-admin"), Map.of()),
                Clock.fixed(Instant.parse("2026-09-11T09:00:00Z"), ZoneOffset.UTC));

        PlatformCustomerLookupController.CustomerLookupResult result = controller.lookup(
                new PlatformCustomerLookupController.CustomerLookupRequest(PHONE, "customer called about a refund"));

        assertThat(result.tenantsSearched()).isGreaterThanOrEqualTo(2);
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.tenantId()).isEqualTo(SECOND);
            assertThat(match.accountId()).isEqualTo(account);
        });
        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.actionCode()).isEqualTo("customer.phone_lookup.performed");
            assertThat(fact.reason()).isEqualTo("customer called about a refund");
            assertThat(fact.changeDocument().toString()).doesNotContain("901234567");
        });
    }

    private static void tenant(JdbcClient jdbc, UUID id, String slug, String name) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).param("name", name).update();
    }
}
