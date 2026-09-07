package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * V0175: {@code tenant.channel_payment_methods.payment_method_code} is a
 * foreign key onto {@code payments.payment_methods (tenant_id, code)}.
 *
 * <p>ADR 0036's own words for what this closes: "a channel row enables or
 * disables a method the tenant has already registered and can never invent
 * one." Before V0175 the column was free text — {@code
 * theSystemTypeSetIsClosed} in {@code SalesChannelAndServiceabilityTests}
 * proves the sibling {@code system_type} column has exactly this kind of
 * database-level guard; this class is the same proof for the payment-method
 * column, which had none until now.
 *
 * <p>Against a real PostgreSQL: what is being asserted is a constraint the
 * database enforces, not application logic that could be reimplemented
 * in-memory without saying anything about the schema.
 */
class ChannelPaymentMethodRegistryConstraintTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this constraint test");
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

        insertTenant(TENANT, "channel-fk-tenant");
        insertTenant(OTHER_TENANT, "channel-fk-other-tenant");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", CHANNEL).param("tenantId", TENANT).update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void registerPaymentMethod(UUID tenantId, String code, String responsibility) {
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                VALUES (:id, :tenantId, :code, :code, :responsibility, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", code)
                .param("responsibility", responsibility)
                .update();
    }

    private Throwable enableOnChannel(UUID tenantId, UUID channelId, String code) {
        return catchThrowable(() -> jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, :code, true)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("code", code)
                .update());
    }

    @Test
    @DisplayName("a channel cannot enable a payment method code with no registry row for this tenant")
    void refusesAnUnregisteredCode() {
        assertThat(enableOnChannel(TENANT, CHANNEL, "CLICK"))
                .as("CLICK was never registered in payments.payment_methods for this tenant")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_channel_payment_method_code");

        assertThat(jdbc.sql("SELECT count(*) FROM tenant.channel_payment_methods WHERE channel_id = :c")
                        .param("c", CHANNEL)
                        .query(Long.class)
                        .single())
                .as("the refused insert must not have landed a row")
                .isZero();
    }

    @Test
    @DisplayName("a channel enables a payment method once the tenant has registered it")
    void acceptsARegisteredCode() {
        registerPaymentMethod(TENANT, "CLICK", "PARTNER");

        assertThat(enableOnChannel(TENANT, CHANNEL, "CLICK")).isNull();

        assertThat(jdbc.sql("""
                        SELECT enabled FROM tenant.channel_payment_methods
                        WHERE channel_id = :c AND payment_method_code = 'CLICK'
                        """).param("c", CHANNEL).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    @DisplayName("another tenant's registered code does not satisfy this tenant's foreign key")
    void aRegistrationOnAnotherTenantDoesNotCrossOver() {
        // The proof that the reference is the composite (tenant_id, code) unique
        // constraint and not code alone: OTHER_TENANT registers CLICK, and this
        // tenant's channel -- which never registered it -- is still refused.
        registerPaymentMethod(OTHER_TENANT, "CLICK", "PARTNER");

        assertThat(enableOnChannel(TENANT, CHANNEL, "CLICK"))
                .as("CLICK belongs to another tenant's registry, not this one's")
                .isInstanceOf(DataIntegrityViolationException.class);

        // And once this tenant registers its own row under the same code, the
        // identical insert that failed a moment ago now succeeds -- proving the
        // refusal above was about tenant scope, not the code itself.
        registerPaymentMethod(TENANT, "CLICK", "PARTNER");
        assertThat(enableOnChannel(TENANT, CHANNEL, "CLICK")).isNull();
    }
}
