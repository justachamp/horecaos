package uz.horecaos.platform.pricing.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * T15 fix4: {@link CustomerDiscountHistoryController}'s HTTP-level coverage.
 * {@code CustomerDiscountHistoryControllerTests} (same package) proves the
 * controller's own totalsRedeemed arithmetic against a fake port, but never
 * through Spring Security or a real database — so neither {@code
 * @RequiresCapability(PRICING_READ)} nor {@code
 * JdbcPromoCodeStore#redemptionsForCustomer}'s {@code WHERE tenant_id =
 * :tenantId} predicate had ever been exercised. This class does both, the
 * same shape {@code BenefitGrantControllerEndpointTests} uses for the other
 * {@code PRICING_READ} endpoint: a caller lacking the capability must be
 * refused, and a caller who legitimately holds it for one tenant must never
 * see another tenant's {@code coupon_redemptions} row even when the
 * {@code customerAccountId} collides across tenants.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerDiscountHistoryEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000c2");
    private static final UUID LOCATION = UUID.fromString("018f9b20-4000-7000-8000-0000000000c3");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000d1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000d2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9b20-4000-7000-8000-0000000000d3");

    /** Deliberately the same id under both tenants — the collision the isolation test targets. */
    private static final UUID CUSTOMER = UUID.fromString("018f9b20-4000-7000-8000-0000000000e1");

    private static final String OWNER = "discount-history-owner";
    private static final String DISPATCHER = "discount-history-dispatcher";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        seedTenantBrandLocation(TENANT, BRAND, LOCATION, "discount-history-endpoint");
        seedTenantBrandLocation(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "discount-history-endpoint-other");
        // TENANT_OWNER carries PRICING_READ (same grant BenefitGrantControllerEndpointTests
        // relies on); COURIER_DISPATCHER carries neither pricing capability.
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, "BRAND", BRAND);
    }

    @Test
    void readingWithoutPricingReadIsRefused() throws Exception {
        MvcResult refused = mvc.perform(
                        get(discountHistoryPath(TENANT, CUSTOMER)).with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.PRICING_READ.code());
    }

    @Test
    void anOwnerReadsTheirOwnTenantsRedemptionRow() throws Exception {
        seedRedemption(TENANT, BRAND, LOCATION, CUSTOMER, "CASHBACK10", "10% cashback", 15_000L);

        MvcResult ok = mvc.perform(get(discountHistoryPath(TENANT, CUSTOMER)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body)
                .contains("10% cashback")
                .contains("\"status\":\"REDEEMED\"")
                .contains("\"amountMinor\":15000")
                .contains("\"totalsRedeemed\":[{\"currency\":\"UZS\",\"amountMinor\":15000}]");
    }

    @Test
    @DisplayName("redemptionsForCustomer's tenant scoping holds through the real SQL join: a colliding "
            + "customerAccountId under another tenant never leaks that tenant's redemption")
    void discountHistoryNeverLeaksAnotherTenantsRedemptionEvenWhenTheCustomerIdCollides() throws Exception {
        // Same CUSTOMER id, but the redemption lives under OTHER_TENANT.
        seedRedemption(
                OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, CUSTOMER, "OTHERTENANT10", "Other tenant's offer", 99_000L);

        MvcResult ok = mvc.perform(get(discountHistoryPath(TENANT, CUSTOMER)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(ok.getResponse().getStatus())
                .as("OWNER is authorized on TENANT, so the request itself is not refused")
                .isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString())
                .as("the join must filter by tenant_id, not just customer_account_id")
                .contains("\"redemptions\":[]")
                .contains("\"totalsRedeemed\":[]")
                .doesNotContain("Other tenant's offer");
    }

    // ------------------------------------------------------------------ fixtures

    private static String discountHistoryPath(UUID tenantId, UUID customerAccountId) {
        return "/api/v1/operations/tenants/" + tenantId + "/customers/" + customerAccountId + "/discount-history";
    }

    private void seedTenantBrandLocation(UUID tenantId, UUID brandId, UUID locationId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'main', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }

    /**
     * The full parent chain a {@code coupon_redemptions} row's foreign keys
     * require: a {@code DRAFT} promotion (skips the validated/activated-at
     * checks an {@code ACTIVE} one would need), its coupon, and the quote the
     * redemption is reserved against. {@code customerAccountId} carries no
     * foreign key at all (see V0093), so it is never seeded separately —
     * exactly what lets the isolation test use a colliding id across tenants.
     */
    private void seedRedemption(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID customerAccountId,
            String promotionCode,
            String promotionName,
            long amountMinor) {
        Instant now = Instant.now();
        UUID promotionId = UUID.randomUUID();
        UUID couponId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO pricing.promotions (
                    id, tenant_id, brand_id, code, name, scope, status, stacking_group,
                    requires_coupon, currency, valid_from)
                VALUES (:id, :tenantId, :brandId, :code, :name, 'ORDER', 'DRAFT', 'default',
                    true, 'UZS', :validFrom)
                """)
                .param("id", promotionId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", promotionCode)
                .param("name", promotionName)
                .param("validFrom", now.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO pricing.coupon_codes (
                    id, tenant_id, brand_id, promotion_id, normalized_code_hash, code_hint,
                    status, maximum_per_customer, valid_from)
                VALUES (:id, :tenantId, :brandId, :promotionId, :hash, :hint, 'ACTIVE', 1, :validFrom)
                """)
                .param("id", couponId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("promotionId", promotionId)
                .param("hash", "0123456789abcdef".repeat(4))
                .param("hint", promotionCode.length() > 8 ? promotionCode.substring(0, 8) : promotionCode)
                .param("validFrom", now.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (
                    id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash,
                    subtotal_minor, tax_minor, fee_minor, discount_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE',
                    :publicationId, 1, 'discount-history-endpoint-ctx',
                    :amountMinor, 0, 0, :amountMinor, :amountMinor, :expiresAt)
                """)
                .param("id", quoteId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("publicationId", UUID.randomUUID())
                .param("amountMinor", amountMinor)
                .param("expiresAt", now.plus(Duration.ofMinutes(15)).atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO pricing.coupon_redemptions (
                    id, tenant_id, brand_id, coupon_id, promotion_id, customer_account_id,
                    quote_id, order_id, status, amount_minor, currency, reserved_at, redeemed_at)
                VALUES (:id, :tenantId, :brandId, :couponId, :promotionId, :customerId,
                    :quoteId, :orderId, 'REDEEMED', :amountMinor, 'UZS', :reservedAt, :redeemedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("couponId", couponId)
                .param("promotionId", promotionId)
                .param("customerId", customerAccountId)
                .param("quoteId", quoteId)
                .param("orderId", UUID.randomUUID())
                .param("amountMinor", amountMinor)
                .param("reservedAt", now.minus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC))
                .param("redeemedAt", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'discount history endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
