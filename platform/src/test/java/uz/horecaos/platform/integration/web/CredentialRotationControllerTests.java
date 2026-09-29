package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;
import uz.horecaos.platform.tenancy.application.onboarding.OnboardingReadinessChecks;

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

    /**
     * The readiness panel's rotation check says it applies exactly this list's rule, so the two
     * cannot disagree. Neither side is the oracle for the other, so both read one fixture and the
     * expected credentials are written out: a retired installation and a retired merchant binding
     * are old enough to be due and are named by neither.
     */
    @Test
    void theCredentialsDueListAndTheReadinessPanelNameTheSameCredentials() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        UUID tenant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Parity', 'Parity', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenant)
                .param("slug", "parity-" + tenant.toString().substring(0, 8))
                .update();
        UUID brand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'MAIN', 'ACTIVE', 0)
                """)
                .param("id", brand)
                .param("tenantId", tenant)
                .param("slug", "b-" + brand.toString().substring(0, 8))
                .update();
        String environment = jdbc.sql("""
                        SELECT code FROM integration.provider_environments WHERE provider_category = 'NOTIFICATION' LIMIT 1
                        """).query(String.class).single();
        Instant old = NOW.minus(Duration.ofDays(400));
        installation(jdbc, tenant, environment, "Due gateway", "ACTIVE", old, null);
        installation(jdbc, tenant, environment, "Rotated gateway", "ACTIVE", old, NOW.minus(Duration.ofDays(10)));
        installation(jdbc, tenant, environment, "Retired gateway", "RETIRED", old, null);
        Instant older = NOW.minus(Duration.ofDays(300));
        merchantBinding(jdbc, tenant, brand, "ACME", "CLICK", "ACTIVE", older, null);
        merchantBinding(jdbc, tenant, brand, "BETA", "PAYME", "RETIRED", older, null);
        merchantBinding(jdbc, tenant, brand, "GAMMA", "TELEGRAM", "ACTIVE", older, NOW.minus(Duration.ofDays(5)));

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Duration interval = Duration.ofDays(180);
        List<String> fromTheList = new CredentialRotationController(jdbc, clock, interval)
                .due(tenant).credentials().stream()
                        .map(credential -> credential.kind() + " " + credential.daysOld() + " days")
                        .sorted()
                        .toList();
        StepResult panel = new OnboardingReadinessChecks.SecretRotationAge(jdbc, clock, interval).check(tenant);
        List<String> fromThePanel = findings(panel).stream()
                .map(finding -> kindOf(finding) + " " + daysIn(finding) + " days")
                .sorted()
                .toList();

        assertThat(fromTheList)
                .as("the control plane's list: the live installation and the live merchant account")
                .containsExactly("INSTALLATION 400 days", "MERCHANT_ACCOUNT 300 days");
        assertThat(fromThePanel).as("the settings panel names the same two").isEqualTo(fromTheList);
    }

    @SuppressWarnings("unchecked")
    private static List<StepResult.Finding> findings(StepResult result) {
        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        return (List<StepResult.Finding>) Objects.requireNonNull(result.result().get(StepResult.FINDINGS_KEY));
    }

    private static String kindOf(StepResult.Finding finding) {
        return switch (finding.errorCode()) {
            case "INSTALLATION_SECRET_ROTATION_DUE" -> "INSTALLATION";
            case "MERCHANT_SECRET_ROTATION_DUE" -> "MERCHANT_ACCOUNT";
            default -> throw new AssertionError("unexpected finding " + finding.errorCode());
        };
    }

    private static String daysIn(StepResult.Finding finding) {
        Matcher matcher = Pattern.compile("(\\d+) days old").matcher(finding.detail());
        assertThat(matcher.find()).as(finding.detail()).isTrue();
        return matcher.group(1);
    }

    /** A merchant binding of the tenant's legal entity {@code entityCode} (created here) with its own installation. */
    private static void merchantBinding(
            JdbcClient jdbc,
            UUID tenant,
            UUID brand,
            String entityCode,
            String providerType,
            String status,
            Instant createdAt,
            @Nullable Instant rotatedAt) {
        UUID legalEntity = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, vat_registered, status)
                VALUES (:id, :tenantId, :code, :legalName, :tin, false, 'ACTIVE')
                """)
                .param("id", legalEntity)
                .param("tenantId", tenant)
                .param("code", entityCode)
                .param("legalName", entityCode + " LLC")
                .param("tin", "%09d".formatted(Math.abs((long) entityCode.hashCode()) % 1_000_000_000L))
                .update();
        String environment = "env-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'PAYMENT', :providerType, 'https://example.test', false, 'example.test')
                """)
                .param("code", environment)
                .param("providerType", providerType)
                .update();
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, 'PAYMENT', :providerType, :env, :name, 'ACTIVE')
                """)
                .param("id", installationId)
                .param("tenantId", tenant)
                .param("providerType", providerType)
                .param("env", environment)
                .param("name", providerType + " installation")
                .update();
        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", bindingId)
                .param("tenantId", tenant)
                .param("installationId", installationId)
                .param("brandId", brand)
                .update();
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings
                    (id, tenant_id, legal_entity_id, provider_type, installation_id, binding_id,
                     merchant_account_reference, secret_reference, callback_path_segment,
                     supports_reversal, supports_partner_fiscalization, status, effective_from,
                     created_at, last_secret_rotated_at)
                VALUES (:id, :tenantId, :legalEntityId, :providerType, :installationId, :bindingId,
                        :account, :secretRef, :segment, true, true, :status, :from, :createdAt, :rotatedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant)
                .param("legalEntityId", legalEntity)
                .param("providerType", providerType)
                .param("installationId", installationId)
                .param("bindingId", bindingId)
                .param("account", "acct-" + legalEntity)
                .param("secretRef", "horecaos:test:provider_payment:tenant:" + legalEntity)
                .param("segment", "seg-" + UUID.randomUUID().toString().substring(0, 10))
                .param("status", status)
                .param("from", LocalDate.of(2025, 1, 1))
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param("rotatedAt", rotatedAt == null ? null : OffsetDateTime.ofInstant(rotatedAt, ZoneOffset.UTC))
                .update();
    }

    private static UUID installation(
            JdbcClient jdbc, String environment, String name, Instant createdAt, @Nullable Instant rotatedAt) {
        return installation(jdbc, TENANT, environment, name, "ACTIVE", createdAt, rotatedAt);
    }

    private static UUID installation(
            JdbcClient jdbc,
            UUID tenant,
            String environment,
            String name,
            String status,
            Instant createdAt,
            @Nullable Instant rotatedAt) {
        UUID id = UUID.randomUUID();
        String providerType = jdbc.sql("SELECT provider_type FROM integration.provider_environments WHERE code = :code")
                .param("code", environment)
                .query(String.class)
                .single();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category, provider_type,
                    environment_code, display_name, status, secret_reference, created_at, last_secret_rotated_at,
                    external_account_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', :providerType, :environment, :name, :status,
                    :secret, :createdAt, :rotatedAt, :account)
                """)
                .param("id", id)
                .param("tenantId", tenant)
                .param("status", status)
                .param("providerType", providerType)
                .param("environment", environment)
                .param("name", name)
                .param("secret", "horecaos:" + tenant + ":provider_notification:" + id + ":v1")
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param("rotatedAt", rotatedAt == null ? null : OffsetDateTime.ofInstant(rotatedAt, ZoneOffset.UTC))
                .param("account", name)
                .update();
        return id;
    }
}
