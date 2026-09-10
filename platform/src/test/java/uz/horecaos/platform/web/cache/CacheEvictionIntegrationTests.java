package uz.horecaos.platform.web.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;
import uz.horecaos.platform.ordering.application.OrderAcceptancePolicyService;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.ApprovalChannel;
import uz.horecaos.platform.ordering.domain.ApprovalTimeoutAction;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;

/**
 * ADR 0033, against real Spring-managed beans rather than hand-constructed
 * ones. Every other test of {@code JdbcConfigurationResolver}, {@code
 * JdbcPolicyResolver}, and {@code JdbcPolicyAuthor} builds them directly
 * ({@code new JdbcXxx(jdbc, ...)}), which never goes through the CGLIB proxy
 * Spring's {@code @Cacheable}/{@code @CacheEvict} rely on — those tests would
 * stay green whether or not this ADR's caching actually works. This class
 * exists to give the caching itself, not just the SQL underneath it, a test
 * that can fail.
 */
@SpringBootTest
class CacheEvictionIntegrationTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac129001");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the cache eviction integration test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ConfigurationResolver configurationResolver;

    @Autowired
    private ConfigurationValueAuthor configurationValues;

    @Autowired
    private OrderAcceptancePolicyService orderAcceptancePolicy;

    @Autowired
    private JdbcProviderEnvironmentLookup providerEnvironments;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'cache-eviction-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    /**
     * A direct SQL change, the way an operator's one-off fix or a migration
     * would write this table, bypasses the cache exactly like it always did —
     * {@code JdbcConfigurationValueAuthor} (below) is the only writer that
     * evicts, because it is the only writer that knows which scope it just
     * changed. Proves the cache is genuinely active, not merely annotated,
     * which is the whole of what makes the sixty-second TTL a real backstop
     * rather than a comment nobody is testing.
     */
    @Test
    void aResolvedConfigurationValueStaysCachedAcrossADirectDatabaseChange() {
        // A synthetic code, deliberately not any registered key: eviction
        // behavior is agnostic to which key is involved. (Not
        // ordering.approval_timeout_seconds, which this literal used to reuse
        // before that key was deleted 2026-09-10.)
        ConfigurationKey<Integer> key = ConfigurationKey.of("testing.cache_probe_seconds", Integer.class)
                .defaultValue(600)
                .build();
        ResourceScope scope = ResourceScope.tenant(TENANT);
        insertConfigValue(key.code(), 111);

        assertThat(configurationResolver.resolve(key, scope).value())
                .as("first read populates the cache")
                .isEqualTo(111);

        // Bypasses the resolver entirely, exactly as an operator's direct SQL
        // change would.
        jdbc.sql("UPDATE tenant.configuration_values SET integer_value = 222 WHERE key_code = :code")
                .param("code", key.code())
                .update();

        assertThat(configurationResolver.resolve(key, scope).value())
                .as("a real Cacheable proxy keeps answering the cached value; only removing "
                        + "@Cacheable from JdbcConfigurationResolver#resolve would turn this 111 into 222")
                .isEqualTo(111);
    }

    /**
     * The scenario this wave closes: before {@code JdbcConfigurationValueAuthor}
     * existed, {@code tenant.configuration_values} had no writer at all, so
     * nothing could evict {@code tenant.configuration} and the sixty-second TTL
     * was the only thing bounding staleness. Every step below runs through real
     * Spring-managed {@code JdbcConfigurationResolver}/{@code
     * JdbcConfigurationValueAuthor} beans, so the eviction call actually reaches
     * the same Caffeine cache the read is served from — the same shape {@code
     * authoringASecondPolicyVersionIsVisibleOnTheVeryNextResolutionThroughTheRealCache}
     * proves for policies, below.
     */
    @Test
    void aValueSetThroughTheAuthorIsVisibleOnTheVeryNextResolutionThroughTheRealCache() {
        // A synthetic code, deliberately not any registered key: eviction
        // behavior is agnostic to which key is involved. (Not
        // ordering.approval_timeout_seconds, which this literal used to reuse
        // before that key was deleted 2026-09-10.)
        ConfigurationKey<Integer> key = ConfigurationKey.of("testing.cache_probe_seconds", Integer.class)
                .defaultValue(600)
                .build();
        ResourceScope scope = ResourceScope.tenant(TENANT);

        configurationValues.set(key, scope, 111, false, null, ActorRef.user("owner-1", null), "initial");
        assertThat(configurationResolver.resolve(key, scope).value())
                .as("first resolution populates the real cache")
                .isEqualTo(111);

        configurationValues.set(key, scope, 222, false, 0L, ActorRef.user("owner-1", null), "shorten the window");

        assertThat(configurationResolver.resolve(key, scope).value())
                .as("a missing @CacheEvict on JdbcConfigurationResolver#evict, or a missing call "
                        + "to it from JdbcConfigurationValueAuthor#set, would leave this reading "
                        + "the sixty-second-old 111")
                .isEqualTo(222);
    }

    /**
     * A provider's base URL is read fresh, every time.
     *
     * <p>This test asserted the opposite for one afternoon, and the assertion
     * was the bug: `integration.environments` was registered with "deployment"
     * as its invalidation source, which nothing in the application can perform,
     * so a base URL that moved stayed moved for an hour. The first thing that
     * hit was `anOwnerRotatesToAReferenceThatResolvesAndPassesGetMe`, which
     * rotates a secret and then calls the gateway — and got a 422, because the
     * gateway had a new address and the cache still held the old one.
     */
    @Test
    void aProviderEnvironmentBaseUrlIsReadFreshRatherThanCached() {
        insertEnvironment("cache-eviction-env", "https://one.example");

        assertThat(providerEnvironments.baseUrlOf("cache-eviction-env")).contains("https://one.example");

        jdbc.sql("UPDATE integration.provider_environments SET base_url = 'https://two.example' WHERE code = :code")
                .param("code", "cache-eviction-env")
                .update();

        assertThat(providerEnvironments.baseUrlOf("cache-eviction-env"))
                .as("putting @Cacheable back on JdbcProviderEnvironmentLookup#baseUrlOf would "
                        + "turn this two.example back into one.example, and would take the "
                        + "secret-rotation endpoint down with it")
                .contains("https://two.example");
    }

    /**
     * The scenario ADR 0033's gap named directly: before {@code
     * JdbcPolicyAuthor} evicted {@code tenant.policy_current}, the first policy
     * change an operator made would keep resolving to the version it replaced
     * for up to the registry's sixty-second TTL. Every step below runs through
     * real Spring-managed {@code JdbcPolicyResolver}/{@code JdbcPolicyAuthor}
     * beans, so the eviction call actually reaches the same Caffeine cache the
     * read is served from — not a fake capturing a method call, which {@code
     * OrderAcceptancePolicyServiceTests} already covers separately.
     */
    @Test
    void authoringASecondPolicyVersionIsVisibleOnTheVeryNextResolutionThroughTheRealCache() {
        ResourceScope scope = ResourceScope.tenant(TENANT);

        orderAcceptancePolicy.author(scope, approval(600), ActorRef.user("owner-1", null), "initial");
        assertThat(orderAcceptancePolicy.resolveAt(scope).policy().approvalTimeoutSeconds())
                .as("first resolution populates the real cache")
                .isEqualTo(600);

        orderAcceptancePolicy.author(scope, approval(60), ActorRef.user("owner-1", null), "shorten the window");

        assertThat(orderAcceptancePolicy.resolveAt(scope).policy().approvalTimeoutSeconds())
                .as("a missing @CacheEvict on JdbcPolicyResolver#evict, or a missing call to it "
                        + "from JdbcPolicyAuthor#author, would leave this reading the sixty-second-old 600")
                .isEqualTo(60);
    }

    private static OrderAcceptancePolicy approval(int timeoutSeconds) {
        return new OrderAcceptancePolicy(
                AcceptanceMode.RESTAURANT_APPROVAL,
                ApprovalChannel.EITHER,
                timeoutSeconds,
                ApprovalTimeoutAction.AUTO_REJECT,
                true,
                true);
    }

    private void insertConfigValue(String keyCode, int value) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, value_type, integer_value, set_by)
                VALUES (:id, :keyCode, 'TENANT', :tenantId, 'INTEGER', :value, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("keyCode", keyCode)
                .param("tenantId", TENANT)
                .param("value", value)
                .update();
    }

    private void insertEnvironment(String code, String baseUrl) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'PAYMENT', 'cache-eviction-test', :baseUrl, false, 'provider.example')
                ON CONFLICT DO NOTHING
                """).param("code", code).param("baseUrl", baseUrl).update();
    }
}
