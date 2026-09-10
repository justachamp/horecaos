package uz.horecaos.platform.iam.infrastructure.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * The question tenancy asks before deleting a draft brand or location: does
 * anyone still hold access scoped to it. Counted against the real table,
 * because what makes a grant "still held" — its status and its validity
 * window — is a WHERE clause, and a fake would only repeat the author's
 * belief about it.
 */
class JdbcScopedGrantDirectoryTests {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120500");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120501");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120502");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120503");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120504");
    private static final UUID ROLE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120505");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcScopedGrantDirectory directory;

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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        var tenants = new JdbcTenantControlPlaneStore(jdbc);
        tenants.insertTenant(tenant(TENANT, "grants-tenant"));
        tenants.insertTenant(tenant(OTHER_TENANT, "grants-other"));
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, NULL, 'scoped-grant-test', 'Scoped grant test', 'BRAND', 'ACTIVE', true)
                """).param("id", ROLE).update();
        directory = new JdbcScopedGrantDirectory(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void countsWhatCanStillAuthoriseOnExactlyThatUnit() {
        grant(TENANT, "BRAND", BRAND, "ACTIVE", NOW.minus(Duration.ofDays(1)), null);
        grant(TENANT, "BRAND", BRAND, "ACTIVE", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        grant(TENANT, "BRAND", BRAND, "ACTIVE", NOW.plus(Duration.ofDays(7)), null);

        assertThat(directory.activeGrantsScopedTo(ResourceScope.brand(TENANT, BRAND)))
                .as("open-ended, still-in-window, and one dated to start next week -- "
                        + "which would start against a brand that no longer exists")
                .isEqualTo(3);
    }

    @Test
    void historyAndNeighboursAreNotCounted() {
        grant(TENANT, "BRAND", BRAND, "REVOKED", NOW.minus(Duration.ofDays(3)), null);
        grant(TENANT, "BRAND", BRAND, "ACTIVE", NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(1)));
        grant(TENANT, "BRAND", OTHER_BRAND, "ACTIVE", NOW.minus(Duration.ofDays(1)), null);
        grant(TENANT, "TENANT", TENANT, "ACTIVE", NOW.minus(Duration.ofDays(1)), null);
        grant(TENANT, "LOCATION", LOCATION, "ACTIVE", NOW.minus(Duration.ofDays(1)), null);

        assertThat(directory.activeGrantsScopedTo(ResourceScope.brand(TENANT, BRAND)))
                .as("revoked and expired are history; another brand, the whole tenant and a "
                        + "location under it are other scopes -- deleting this brand strands none of them")
                .isZero();
        assertThat(directory.activeGrantsScopedTo(ResourceScope.location(TENANT, BRAND, LOCATION)))
                .isEqualTo(1);
    }

    @Test
    void anotherTenantsGrantIsNeverCountedAgainstThisOnesUnit() {
        grant(OTHER_TENANT, "BRAND", BRAND, "ACTIVE", NOW.minus(Duration.ofDays(1)), null);

        assertThat(directory.activeGrantsScopedTo(ResourceScope.brand(TENANT, BRAND)))
                .isZero();
    }

    @Test
    void onlyABrandOrLocationCanBeAskedAbout() {
        assertThatThrownBy(() -> directory.activeGrantsScopedTo(ResourceScope.tenant(TENANT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void grant(
            UUID tenantId,
            String scopeType,
            UUID scopeId,
            String status,
            Instant validFrom,
            @Nullable Instant validUntil) {
        // A principal of its own per grant: uq_grant_active allows one active
        // grant per principal, role and scope, and these are several people's.
        // A revoked row carries the revocation trio ck_grant_revocation_pair
        // requires, as GrantManagementService#revoke writes it.
        boolean revoked = "REVOKED".equals(status);
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from, valid_until,
                     revoked_at, revoked_by, revoked_reason)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        :status, 'test-fixture', 'scoped grant directory test', :validFrom, :validUntil,
                        :revokedAt, :revokedBy, :revokedReason)
                """)
                .param("id", UUID.randomUUID())
                .param("subject", "subject-" + UUID.randomUUID())
                .param(
                        "revokedAt",
                        revoked ? validFrom.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC) : null,
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("revokedBy", revoked ? "test-fixture" : null, Types.VARCHAR)
                .param("revokedReason", revoked ? "left the company" : null, Types.VARCHAR)
                .param("tenantId", tenantId)
                .param("roleId", ROLE)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("status", status)
                .param("validFrom", validFrom.atOffset(ZoneOffset.UTC))
                .param(
                        "validUntil",
                        validUntil == null ? null : validUntil.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static Tenant tenant(UUID id, String slug) {
        return Tenant.provision(
                new TenantId(id),
                new Slug(slug),
                "Tenant LLC",
                "Tenant",
                Currency.getInstance("UZS"),
                ZoneId.of("Asia/Tashkent"));
    }
}
