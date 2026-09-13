package uz.horecaos.platform.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantCreated;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * ADR 0038's onboarding checklist line "seed each tenant's methods" (row
 * 10.6). Without this listener a freshly onboarded tenant registers nothing,
 * and {@code tenant.channel_payment_methods.payment_method_code}'s foreign
 * key (V0175) refuses the very first payment-method checkbox the pilot's own
 * settings walkthrough would click.
 */
class PaymentMethodOnboardingSeederTests {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcSettlementStore store;
    private PaymentMethodOnboardingSeeder seeder;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the onboarding seeder test");
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

        store = new JdbcSettlementStore(jdbc);
        seeder = new PaymentMethodOnboardingSeeder(
                store, Clock.fixed(Instant.parse("2026-09-13T09:00:00Z"), ZoneOffset.UTC));
    }

    private TenantCreated tenantCreated(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "seeder-tenant-" + tenantId)
                .update();
        return new TenantCreated(
                UUID.randomUUID(),
                new TenantId(tenantId),
                Instant.parse("2026-09-13T09:00:00Z"),
                "seeder-tenant",
                "Legal",
                "Display",
                "UZS",
                "Asia/Tashkent",
                "ACTIVE",
                "OTP");
    }

    @Test
    @DisplayName(
            "a new tenant is seeded with CASH, CLICK and PAYME, each already carrying the V0175 backfill's own responsibility")
    void seedsTheThreeStarterMethods() {
        UUID tenantId = UUID.randomUUID();
        seeder.on(tenantCreated(tenantId));

        assertThat(store.listMethodsForTenant(tenantId))
                .extracting(JdbcSettlementStore.MethodRow::code)
                .containsExactlyInAnyOrder("CASH", "CLICK", "PAYME");

        assertThat(store.findMethodByCode(tenantId, "CASH")).get().satisfies(cash -> {
            assertThat(cash.responsibility()).isEqualTo("OPERATOR");
            assertThat(cash.status()).isEqualTo("ACTIVE");
        });
        assertThat(store.findMethodByCode(tenantId, "CLICK")).get().satisfies(click -> {
            assertThat(click.responsibility()).isEqualTo("PARTNER");
        });
        assertThat(store.findMethodByCode(tenantId, "PAYME")).get().satisfies(payme -> {
            assertThat(payme.responsibility()).isEqualTo("PARTNER");
        });
    }

    @Test
    @DisplayName("a replayed TenantCreated -- Spring's in-process retry included -- registers nothing a second time")
    void isIdempotentUnderReplay() {
        UUID tenantId = UUID.randomUUID();
        TenantCreated event = tenantCreated(tenantId);

        seeder.on(event);
        seeder.on(event);

        assertThat(store.listMethodsForTenant(tenantId)).hasSize(3);
    }

    @Test
    @DisplayName("an operator who already registered CASH before this listener ran keeps their own row untouched")
    void doesNotOverwriteAnAlreadyRegisteredMethod() {
        UUID tenantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", "already-seeded").update();
        UUID handTypedId = store.registerMethod(tenantId, "CASH", "Наличные", "OPERATOR", false, Instant.now());

        seeder.on(new TenantCreated(
                UUID.randomUUID(),
                new TenantId(tenantId),
                Instant.parse("2026-09-13T09:00:00Z"),
                "already-seeded",
                "Legal",
                "Display",
                "UZS",
                "Asia/Tashkent",
                "ACTIVE",
                "OTP"));

        assertThat(store.findMethodByCode(tenantId, "CASH")).get().satisfies(cash -> {
            assertThat(cash.id()).isEqualTo(handTypedId);
            assertThat(cash.displayName()).isEqualTo("Наличные");
        });
    }
}
