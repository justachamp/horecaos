package uz.horecaos.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;

/**
 * What decides whether the assistant is there, against the real ADR 0021
 * entitlements and the real ADR 0030 resolver rather than stand-ins -- because the
 * question these tests answer is which of the gates actually holds it dark today,
 * and a stub would answer whichever way its author hoped.
 *
 * <p>The answer is the switch. ADR 0021's pilot is meter-only, under which a
 * feature check cannot refuse, so the plan entitlement reads true for a tenant
 * with no plan (the keys are declared {@code safeDefault(false)} and are still
 * reported as "over, unbilled and allowed"). That is by design and these tests
 * pin it so nobody mistakes the entitlement for the brake.
 */
class AssistantSettingsTests {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private AssistantSettings settings;
    private final UUID tenant = UUID.randomUUID();
    private final UUID brand = UUID.randomUUID();
    private final UUID siblingBrand = UUID.randomUUID();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", "settings-" + tenant).update();
        for (UUID id : new UUID[] {brand, siblingBrand}) {
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                    """)
                    .param("id", id)
                    .param("tenantId", tenant)
                    .param("code", "B" + id.toString().substring(0, 6).toUpperCase())
                    .param("slug", "b-" + id.toString().substring(0, 8))
                    .update();
        }
        settings = new AssistantSettings(
                new JdbcConfigurationResolver(jdbc),
                CommercialDefaults.wire(jdbc, Clock.systemUTC()).entitlements());
    }

    private void set(
            String key,
            String scope,
            @Nullable UUID brandId,
            String type,
            @Nullable Boolean bool,
            @Nullable Long number) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, brand_id, value_type, boolean_value, integer_value, set_by)
                VALUES (:id, :key, :scope, :tenantId, :brandId, :type, :bool, :number, 'a-test')
                """)
                .param("id", UUID.randomUUID())
                .param("key", key)
                .param("scope", scope)
                .param("tenantId", tenant)
                .param("brandId", brandId)
                .param("type", type)
                .param("bool", bool)
                .param("number", number)
                .update();
    }

    @Test
    @DisplayName(
            "a tenant that has set nothing is not answered: the switch defaults off, though the plan check reads true under meter-only")
    void theSwitchIsTheBrake() {
        assertThat(settings.entitled(tenant))
                .as("ADR 0021 meter-only: a feature the plan does not include is counted and allowed, never refused")
                .isTrue();
        assertThat(settings.switchedOn(tenant, brand)).isFalse();
        assertThat(settings.switchedOnForTenant(tenant)).isFalse();
    }

    @Test
    @DisplayName("the switch is set for the tenant and a brand's own value wins for that brand only")
    void theSwitchResolvesDownToTheBrand() {
        set("assistant.enabled", "TENANT", null, "BOOLEAN", true, null);

        assertThat(settings.switchedOn(tenant, brand)).isTrue();
        assertThat(settings.switchedOn(tenant, siblingBrand)).isTrue();

        set("assistant.enabled", "BRAND", siblingBrand, "BOOLEAN", false, null);

        assertThat(settings.switchedOn(tenant, brand)).isTrue();
        assertThat(settings.switchedOn(tenant, siblingBrand))
                .as("the narrower scope wins")
                .isFalse();
    }

    @Test
    @DisplayName("the ceiling and the turn cap carry the documented defaults and take a tenant's own value")
    void theNumbersAreConfigurable() {
        assertThat(settings.monthlySpendCeilingUsdCents(tenant)).isEqualTo(2_500L);
        assertThat(settings.conversationTurnCap(tenant, brand)).isEqualTo(20);
        assertThat(settings.priceChannelCode(tenant, brand)).isEqualTo("STOREFRONT");

        set("assistant.monthly_spend_ceiling_usd_cents", "TENANT", null, "INTEGER", null, 400L);
        set("assistant.conversation_turn_cap", "BRAND", brand, "INTEGER", null, 5L);

        assertThat(settings.monthlySpendCeilingUsdCents(tenant)).isEqualTo(400L);
        assertThat(settings.conversationTurnCap(tenant, brand)).isEqualTo(5);
        assertThat(settings.conversationTurnCap(tenant, siblingBrand)).isEqualTo(20);
    }
}
