package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.ApprovalChannel;
import uz.horecaos.platform.ordering.domain.ApprovalTimeoutAction;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;

/**
 * ADR 0002 acceptance resolution, now running on the ADR 0030 mechanism rather
 * than its own table.
 *
 * <p>These tests are deliberately against the real resolver and real SQL: the
 * point of the migration was to stop having a second precedence implementation,
 * so testing against a stub would test the thing that was removed.
 */
class OrderAcceptancePolicyServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121101");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121102");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121103");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121104");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121105");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private OrderAcceptancePolicyService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        service = new OrderAcceptancePolicyService(
                new JdbcPolicyResolver(jdbc, JsonMapper.builder().build()));
        insertHierarchy();
    }

    @Test
    void fallsBackToAutoConfirmWhenNoPolicyExists() {
        OrderAcceptancePolicyService.Effective effective = service.resolve(TENANT, BRAND, LOCATION);

        assertThat(effective.policy().mode()).isEqualTo(AcceptanceMode.AUTO_CONFIRM);
        assertThat(effective.isPlatformDefault())
                .as("that the platform default applied is itself a fact an order should record")
                .isTrue();
    }

    @Test
    void aLocationPolicyBeatsBrandAndTenant() {
        activate("TENANT", null, null, approval(600));
        activate("BRAND", BRAND, null, approval(300));
        activate("LOCATION", BRAND, LOCATION, approval(90));

        assertThat(service.resolve(TENANT, BRAND, LOCATION).policy().approvalTimeoutSeconds())
                .isEqualTo(90);
    }

    @Test
    void aBrandPolicyAppliesWhereTheLocationHasNone() {
        activate("TENANT", null, null, approval(600));
        activate("BRAND", BRAND, null, approval(300));

        assertThat(service.resolve(TENANT, BRAND, SIBLING_LOCATION).policy().approvalTimeoutSeconds())
                .isEqualTo(300);
    }

    @Test
    void aSiblingBrandsPolicyNeverApplies() {
        activate("TENANT", null, null, approval(600));
        activate("BRAND", OTHER_BRAND, null, approval(90));

        assertThat(service.resolve(TENANT, BRAND, LOCATION).policy().approvalTimeoutSeconds())
                .as("precedence runs up the ancestry, never sideways")
                .isEqualTo(600);
    }

    @Test
    void anOrderKeepsTheVersionItWasAcceptedUnder() {
        activate("TENANT", null, null, approval(600));
        OrderAcceptancePolicyService.Effective atAcceptance = service.resolve(TENANT, BRAND, LOCATION);

        // The tenant later shortens the approval window.
        jdbc.sql("UPDATE tenant.policies SET status = 'RETIRED'").update();
        jdbc.sql("TRUNCATE TABLE tenant.policy_current").update();
        activate("TENANT", null, null, approval(60), 2);

        assertThat(service.resolve(TENANT, BRAND, LOCATION).policy().approvalTimeoutSeconds())
                .as("new orders use the new policy")
                .isEqualTo(60);
        assertThat(service.pinned(atAcceptance.policyId(), atAcceptance.policyVersion())
                        .approvalTimeoutSeconds())
                .as("an accepted order stays explainable under the policy that governed it")
                .isEqualTo(600);
    }

    @Test
    void anOrderOnThePlatformDefaultPinsToTheDefault() {
        OrderAcceptancePolicyService.Effective effective = service.resolve(TENANT, BRAND, LOCATION);

        assertThat(service.pinned(effective.policyId(), effective.policyVersion())
                        .mode())
                .isEqualTo(AcceptanceMode.AUTO_CONFIRM);
    }

    @Test
    void theDomainInvariantsSurvivedTheMigration() {
        assertThatThrownBy(() -> new OrderAcceptancePolicy(
                        AcceptanceMode.AUTO_CONFIRM,
                        ApprovalChannel.POS,
                        0,
                        ApprovalTimeoutAction.AUTO_REJECT,
                        false,
                        false))
                .hasMessageContaining("Auto-confirm policies cannot have an approval channel");

        assertThatThrownBy(() -> new OrderAcceptancePolicy(
                        AcceptanceMode.RESTAURANT_APPROVAL,
                        ApprovalChannel.EITHER,
                        5,
                        ApprovalTimeoutAction.AUTO_REJECT,
                        false,
                        false))
                .hasMessageContaining("between 30 seconds and 30 minutes");
    }

    @Test
    void theSpecialisedTableIsGone() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'ordering' AND table_name = 'order_acceptance_policies'
                """).query(Long.class).single())
                .as("two implementations of one rule is the condition ADR 0030 exists to remove")
                .isZero();
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

    private void activate(String scopeType, UUID brandId, UUID locationId, OrderAcceptancePolicy policy) {
        activate(scopeType, brandId, locationId, policy, 1);
    }

    private void activate(String scopeType, UUID brandId, UUID locationId, OrderAcceptancePolicy policy, int version) {

        UUID id = UUID.randomUUID();
        String document = """
                {"mode":"%s","approvalChannel":"%s","approvalTimeoutSeconds":%d,
                 "timeoutAction":"%s","rejectionReasonRequired":%b,"notifyCustomerWhilePending":%b}""".formatted(
                        policy.mode(),
                        policy.approvalChannel(),
                        policy.approvalTimeoutSeconds(),
                        policy.timeoutAction(),
                        policy.rejectionReasonRequired(),
                        policy.notifyCustomerWhilePending());

        jdbc.sql("""
                INSERT INTO tenant.policies (
                    id, key_code, scope_type, tenant_id, brand_id, location_id, version, status,
                    document, document_hash, valid_from, created_by)
                VALUES (:id, 'ordering.acceptance', :scopeType, :tenantId, :brandId, :locationId,
                        :version, 'ACTIVE', CAST(:document AS jsonb), :hash, now(), 'test')
                """)
                .param("id", id)
                .param("scopeType", scopeType)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("version", version)
                .param("document", document)
                .param("hash", "%064x".formatted(java.math.BigInteger.valueOf(document.hashCode() & 0xFFFFFFFFL)))
                .update();

        jdbc.sql("""
                INSERT INTO tenant.policy_current (
                    key_code, scope_type, tenant_id, brand_id, location_id,
                    policy_id, policy_version, activated_by)
                VALUES ('ordering.acceptance', :scopeType, :tenantId, :brandId, :locationId,
                        :id, :version, 'test')
                """)
                .param("scopeType", scopeType)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("id", id)
                .param("version", version)
                .update();
    }

    /** These UUIDs share a prefix, so codes derive from the distinguishing tail. */
    private static String suffix(UUID id) {
        String text = id.toString().replace("-", "");
        return text.substring(text.length() - 6);
    }

    private void insertHierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-acceptance', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        for (UUID brand : new UUID[] {BRAND, OTHER_BRAND}) {
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                    """)
                    .param("id", brand)
                    .param("tenantId", TENANT)
                    .param("code", "B" + suffix(brand).toUpperCase())
                    .param("slug", "b-" + suffix(brand))
                    .update();
        }
        for (UUID location : new UUID[] {LOCATION, SIBLING_LOCATION}) {
            jdbc.sql("""
                    INSERT INTO tenant.locations
                        (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", "L" + suffix(location).toUpperCase())
                    .param("slug", "l-" + suffix(location))
                    .update();
        }
    }
}
