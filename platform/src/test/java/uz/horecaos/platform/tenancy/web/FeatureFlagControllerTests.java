package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcFeatureFlagOverview;

/**
 * ADR 0082 against PostgreSQL: a flag is rolled out by the ordinary
 * configuration write, and the two reads report it truthfully — the rollout
 * view lists who is set apart, and a tenant sees its own resolved answer.
 */
class FeatureFlagControllerTests {

    private static final UUID PILOT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1382a1");
    private static final UUID HELD_BACK = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1382a2");
    private static final UUID EVERYONE_ELSE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1382a3");
    private static final String FLAG = ConfigurationKeys.FEATURE_SUPPORT_VISITS.code();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcConfigurationValueAuthor author;
    private FeatureFlagController controller;

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
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        JdbcConfigurationResolver resolver = new JdbcConfigurationResolver(jdbc);
        author = new JdbcConfigurationValueAuthor(
                jdbc,
                fact -> {},
                Clock.fixed(Instant.parse("2026-09-11T03:00:00Z"), ZoneOffset.UTC),
                (key, scope) -> {});
        controller = new FeatureFlagController(resolver, new JdbcFeatureFlagOverview(jdbc));
        tenant(PILOT, "pilot", "Oshxona");
        tenant(HELD_BACK, "held-back", "Somsa Markazi");
        tenant(EVERYONE_ELSE, "everyone-else", "Lavash");
    }

    @Test
    void aFlagNobodyTurnedOnIsOffEverywhere() {
        assertThat(controller.forTenant(PILOT)).containsEntry(FLAG, false);
        assertThat(controller.flags())
                .filteredOn(flag -> flag.code().equals(FLAG))
                .singleElement()
                .satisfies(flag -> {
                    assertThat(flag.defaultValue()).isFalse();
                    assertThat(flag.platformValue()).isNull();
                    assertThat(flag.tenants()).isEmpty();
                });
    }

    @Test
    void onForOnePilotTenantAndNobodyElse() {
        author.set(
                ConfigurationKeys.FEATURE_SUPPORT_VISITS,
                ResourceScope.tenant(PILOT),
                true,
                false,
                null,
                ActorRef.user("op-1", null),
                "first pilot");

        assertThat(controller.forTenant(PILOT)).containsEntry(FLAG, true);
        assertThat(controller.forTenant(EVERYONE_ELSE)).containsEntry(FLAG, false);
        assertThat(controller.flags())
                .filteredOn(flag -> flag.code().equals(FLAG))
                .singleElement()
                .satisfies(flag -> assertThat(flag.tenants()).singleElement().satisfies(setting -> {
                    assertThat(setting.tenantName()).isEqualTo("Oshxona");
                    assertThat(setting.value()).isTrue();
                }));
    }

    @Test
    void onForEveryoneExceptOneHeldBackAndAClearedTenantFollowsThePlatformAgain() {
        author.set(
                ConfigurationKeys.FEATURE_SUPPORT_VISITS,
                ResourceScope.platform(),
                true,
                false,
                null,
                ActorRef.user("op-1", null),
                "general availability");
        author.set(
                ConfigurationKeys.FEATURE_SUPPORT_VISITS,
                ResourceScope.tenant(HELD_BACK),
                false,
                false,
                null,
                ActorRef.user("op-1", null),
                "their owner asked to wait");
        author.set(
                ConfigurationKeys.FEATURE_SUPPORT_VISITS,
                ResourceScope.tenant(PILOT),
                true,
                false,
                null,
                ActorRef.user("op-1", null),
                "was the pilot");
        author.set(
                ConfigurationKeys.FEATURE_SUPPORT_VISITS,
                ResourceScope.tenant(PILOT),
                null,
                true,
                0L,
                ActorRef.user("op-1", null),
                "no longer needs its own setting");

        assertThat(controller.forTenant(EVERYONE_ELSE)).containsEntry(FLAG, true);
        assertThat(controller.forTenant(HELD_BACK)).containsEntry(FLAG, false);
        assertThat(controller.forTenant(PILOT))
                .as("a cleared override hands the tenant back to the platform value")
                .containsEntry(FLAG, true);
        assertThat(controller.flags())
                .filteredOn(flag -> flag.code().equals(FLAG))
                .singleElement()
                .satisfies(flag -> {
                    assertThat(flag.platformValue()).isTrue();
                    assertThat(flag.platformVersion()).isZero();
                    assertThat(flag.tenants())
                            .extracting(
                                    FeatureFlagController.TenantSetting::tenantName,
                                    FeatureFlagController.TenantSetting::value)
                            .containsExactlyInAnyOrder(
                                    org.assertj.core.groups.Tuple.tuple("Somsa Markazi", false),
                                    org.assertj.core.groups.Tuple.tuple("Oshxona", null));
                });
    }

    private void tenant(UUID id, String slug, String name) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).param("name", name).update();
    }
}
