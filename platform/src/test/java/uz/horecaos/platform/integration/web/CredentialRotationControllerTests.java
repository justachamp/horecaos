package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0094: a credential not rotated within the interval is due, counted from its last rotation or its setup. */
class CredentialRotationControllerTests {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-2100-7000-8000-0000000000f1");

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
    void anOldNeverRotatedCredentialIsDueAndARecentlyRotatedOneIsNot() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'creds', 'Creds', 'Creds', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        String environment = jdbc.sql("""
                        SELECT code FROM integration.provider_environments WHERE provider_category = 'NOTIFICATION' LIMIT 1
                        """).query(String.class).single();
        UUID old = installation(jdbc, environment, "Old gateway", NOW.minus(Duration.ofDays(400)), null);
        installation(
                jdbc, environment, "Rotated gateway", NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(10)));

        CredentialRotationController controller =
                new CredentialRotationController(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(180));
        CredentialRotationController.CredentialsDue due = controller.due(TENANT);

        assertThat(due.rotationIntervalDays()).isEqualTo(180);
        assertThat(due.credentials()).singleElement().satisfies(credential -> {
            assertThat(credential.id()).isEqualTo(old);
            assertThat(credential.lastRotatedAt()).isNull();
            assertThat(credential.daysOld()).isEqualTo(400);
        });
    }

    private static UUID installation(
            JdbcClient jdbc, String environment, String name, Instant createdAt, @Nullable Instant rotatedAt) {
        UUID id = UUID.randomUUID();
        String providerType = jdbc.sql("SELECT provider_type FROM integration.provider_environments WHERE code = :code")
                .param("code", environment)
                .query(String.class)
                .single();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category, provider_type,
                    environment_code, display_name, status, secret_reference, created_at, last_secret_rotated_at,
                    external_account_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', :providerType, :environment, :name, 'ACTIVE',
                    :secret, :createdAt, :rotatedAt, :account)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("providerType", providerType)
                .param("environment", environment)
                .param("name", name)
                .param("secret", "horecaos:" + TENANT + ":provider_notification:" + id + ":v1")
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param("rotatedAt", rotatedAt == null ? null : OffsetDateTime.ofInstant(rotatedAt, ZoneOffset.UTC))
                .param("account", name)
                .update();
        return id;
    }
}
