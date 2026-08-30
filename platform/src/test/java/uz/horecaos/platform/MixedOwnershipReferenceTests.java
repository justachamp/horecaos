package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The four references whose target holds platform-owned rows beside tenant-owned
 * ones, asked of PostgreSQL.
 *
 * <p>{@code tools/checks/known_tenant_blind_references.tsv} carried these as
 * MIXED_OWNERSHIP_TARGET from V0077 to V0088, with one argument repeated four
 * times: the target's {@code tenant_id} is nullable on purpose, a foreign key
 * cannot say "this tenant's row or the platform's", and only a resolution rule in
 * the referencing service could close them. V0088 says a key on two DERIVED
 * columns can — {@code coalesce(tenant_id, nil)} on the target, and on the
 * referencing side a declared ownership boolean that can generate only the
 * platform sentinel or the row's own tenant.
 *
 * <p>Each reference gets the same three questions, and all three matter. The
 * cross-tenant write must be refused — that is the defect. The platform row must
 * still be accepted — a rule that closed the hole by forbidding the platform
 * default would break ADR 0030 resolution, V0025's shared regions and ADR 0027's
 * platform-scope approvals in one stroke, and it would pass a test that only
 * asked the first question. And the caller's own row must still be accepted, so
 * the refusal above is about the tenant and not about the reference being
 * unusable.
 *
 * <p>These are assertions about the schema, written as raw SQL on purpose. Every
 * one of the four services resolves correctly today, and every allowlist entry
 * said so; the constraint is what holds for the write paths that are not those
 * services, so a test that went through them would be testing the wrong layer.
 * The service refusals are held where the services are — {@code
 * MigrationCutoverGateTests} for the cutover path.
 */
class MixedOwnershipReferenceTests {

    /**
     * The last migration before V0088, so each defect can be reproduced at rest
     * and then migrated over.
     *
     * <p>Named explicitly rather than computed, for the reason {@code
     * TenantScopedReferenceCatalogTests} gives: "the one before" moves under
     * anyone adding a migration.
     */
    private static final MigrationVersion BEFORE_THE_REPAIR = MigrationVersion.fromVersion("0087");

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to ask the schema");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    // -----------------------------------------------------------------------
    // 1. migration.cutover_decisions -> audit.approval_requests
    // -----------------------------------------------------------------------

    /**
     * The worst consequence in the allowlist: a cutover decision presenting
     * another tenant's approval as its own authorisation, in the append-only table
     * a reviewer reads first.
     */
    @Test
    @DisplayName("a cutover decision cannot cite another tenant's approval request")
    void aCutoverDecisionCannotCiteAnotherTenantsApproval() {
        UUID tenantA = tenant("cutover-a");
        UUID tenantB = tenant("cutover-b");
        UUID scopeA = migrationScope(tenantA);
        UUID approvalB = approvalRequest(tenantB, approvalPolicy(tenantB));

        assertThatThrownBy(() -> cutoverDecision(tenantA, scopeA, approvalB, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_cutover_approval_request");

        // And it cannot be reached by claiming the other tenant's request is the
        // platform's, which is the branch a check on tenant_id alone would miss.
        assertThatThrownBy(() -> cutoverDecision(tenantA, scopeA, approvalB, true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_cutover_approval_request");
    }

    @Test
    @DisplayName("a cutover decision may still cite a PLATFORM-scope approval request")
    void aCutoverDecisionMayCiteThePlatformsApproval() {
        UUID tenantA = tenant("cutover-platform");
        UUID scopeA = migrationScope(tenantA);
        UUID platformApproval = approvalRequest(null, approvalPolicy(null));

        assertThatCode(() -> cutoverDecision(tenantA, scopeA, platformApproval, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a cutover decision may still cite its own tenant's approval request")
    void aCutoverDecisionMayCiteItsOwnApproval() {
        UUID tenantA = tenant("cutover-own");
        UUID scopeA = migrationScope(tenantA);
        UUID ownApproval = approvalRequest(tenantA, approvalPolicy(tenantA));

        assertThatCode(() -> cutoverDecision(tenantA, scopeA, ownApproval, false))
                .doesNotThrowAnyException();
    }

    /**
     * The hole one column to the left.
     *
     * <p>Under MATCH SIMPLE a NULL anywhere in a foreign key stops the check, so a
     * writer that supplied the request id and left the ownership undeclared would
     * generate a NULL owner and buy itself exactly the unchecked reference V0088
     * exists to close. {@code ck_cutover_approval_ownership_declared} is what makes
     * that unreachable, and this is the test that it is not decorative.
     */
    @Test
    @DisplayName("a cutover decision cannot cite an approval request without saying whose it is")
    void aCutoverDecisionCannotLeaveTheOwnershipUndeclared() {
        UUID tenantA = tenant("cutover-undeclared");
        UUID tenantB = tenant("cutover-undeclared-b");
        UUID scopeA = migrationScope(tenantA);
        UUID approvalB = approvalRequest(tenantB, approvalPolicy(tenantB));

        assertThatThrownBy(() -> cutoverDecision(tenantA, scopeA, approvalB, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cutover_approval_ownership_declared");
    }

    // -----------------------------------------------------------------------
    // 2. audit.approval_requests -> audit.approval_policies
    // -----------------------------------------------------------------------

    /**
     * The policy carries {@code required_approver_capability}, so a request naming
     * another tenant's policy imports another tenant's answer to who may sign it.
     */
    @Test
    @DisplayName("an approval request cannot name another tenant's policy")
    void anApprovalRequestCannotNameAnotherTenantsPolicy() {
        UUID tenantA = tenant("policy-a");
        UUID tenantB = tenant("policy-b");
        UUID policyB = approvalPolicy(tenantB);

        assertThatThrownBy(() -> approvalRequest(tenantA, policyB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_approval_request_policy");

        assertThatThrownBy(() -> approvalRequest(tenantA, policyB, true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_approval_request_policy");
    }

    @Test
    @DisplayName("an approval request may still fall back to the PLATFORM-scope policy")
    void anApprovalRequestMayNameThePlatformPolicy() {
        UUID tenantA = tenant("policy-platform");
        UUID platformPolicy = approvalPolicy(null);

        assertThatCode(() -> approvalRequest(tenantA, platformPolicy)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an approval request may still name its own tenant's policy")
    void anApprovalRequestMayNameItsOwnPolicy() {
        UUID tenantA = tenant("policy-own");

        assertThatCode(() -> approvalRequest(tenantA, approvalPolicy(tenantA))).doesNotThrowAnyException();
    }

    /**
     * The nullable-referencing-tenant branch.
     *
     * <p>This table's own {@code tenant_id} is null for a PLATFORM-scope request.
     * Such a request declaring a tenant policy would generate a NULL owner and take
     * the key out of the check entirely, which is why V0088 adds
     * {@code ck_approval_request_policy_ownership} beside the key rather than
     * trusting the key alone.
     */
    @Test
    @DisplayName("a PLATFORM-scope approval request cannot borrow a tenant's policy")
    void aPlatformScopeRequestCannotBorrowATenantPolicy() {
        UUID tenantA = tenant("policy-platform-scope");
        UUID policyA = approvalPolicy(tenantA);

        assertThatThrownBy(() -> approvalRequest(null, policyA, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_request_policy_ownership");
    }

    // -----------------------------------------------------------------------
    // 3. fulfillment.service_zone_versions -> fulfillment.regions
    // -----------------------------------------------------------------------

    /**
     * A region is not decorative: its bounding box is what zone activation checks
     * the polygon against, so another tenant's geography would be gating this
     * tenant's zone.
     */
    @Test
    @DisplayName("a zone version cannot name another tenant's private region")
    void aZoneVersionCannotNameAnotherTenantsRegion() {
        UUID tenantA = tenant("region-a");
        UUID tenantB = tenant("region-b");
        UUID zoneA = serviceZone(tenantA);
        UUID regionB = region(tenantB);

        assertThatThrownBy(() -> zoneVersion(tenantA, zoneA, 1, regionB, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_zone_version_region");

        assertThatThrownBy(() -> zoneVersion(tenantA, zoneA, 2, regionB, true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_zone_version_region");
    }

    @Test
    @DisplayName("a zone version may still name a platform region")
    void aZoneVersionMayNameAPlatformRegion() {
        UUID tenantA = tenant("region-platform");
        UUID zoneA = serviceZone(tenantA);

        assertThatCode(() -> zoneVersion(tenantA, zoneA, 1, region(null), true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a zone version may still name its own tenant's region")
    void aZoneVersionMayNameItsOwnRegion() {
        UUID tenantA = tenant("region-own");
        UUID zoneA = serviceZone(tenantA);

        assertThatCode(() -> zoneVersion(tenantA, zoneA, 1, region(tenantA), false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a zone version with no region at all is still ordinary")
    void aZoneVersionMayNameNoRegion() {
        UUID tenantA = tenant("region-none");
        UUID zoneA = serviceZone(tenantA);

        assertThatCode(() -> zoneVersion(tenantA, zoneA, 1, null, null)).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // 4. tenant.policy_current -> tenant.policies
    // -----------------------------------------------------------------------

    /**
     * The activation pointer, which {@code JdbcPolicyResolver} joins straight to
     * the document it hands the caller. A TENANT-scope pointer for tenant A naming
     * tenant B's policy is tenant A running on tenant B's configuration.
     *
     * <p>This one is exact rather than a disjunction: the resolver selects every
     * pointer in the chain and picks the most specific LEVEL, so the PLATFORM
     * pointer is the fallback and a TENANT pointer means "what this tenant
     * activated at tenant scope". A tenant pointer naming the platform default is
     * therefore refused too, and the test below says so on purpose.
     */
    @Test
    @DisplayName("an activation pointer cannot name another tenant's policy")
    void anActivationPointerCannotNameAnotherTenantsPolicy() {
        UUID tenantA = tenant("current-a");
        UUID tenantB = tenant("current-b");
        String key = "probe.key." + UUID.randomUUID();
        UUID policyB = policy(tenantB, key);

        assertThatThrownBy(() -> policyCurrent(tenantA, key, policyB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_policy_current_policy");
    }

    @Test
    @DisplayName("a PLATFORM pointer names the platform policy, and a tenant pointer its own")
    void eachPointerNamesThePolicyActivatedAtItsOwnScope() {
        UUID tenantA = tenant("current-own");
        String platformKey = "probe.key." + UUID.randomUUID();
        String tenantKey = "probe.key." + UUID.randomUUID();

        assertThatCode(() -> policyCurrent(null, platformKey, policy(null, platformKey)))
                .doesNotThrowAnyException();
        assertThatCode(() -> policyCurrent(tenantA, tenantKey, policy(tenantA, tenantKey)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a tenant pointer cannot name the platform default instead of activating it")
    void aTenantPointerCannotNameThePlatformDefault() {
        UUID tenantA = tenant("current-fallback");
        String key = "probe.key." + UUID.randomUUID();
        UUID platformPolicy = policy(null, key);

        assertThatThrownBy(() -> policyCurrent(tenantA, key, platformPolicy))
                .as("the resolver reaches past a tenant's pointer to the platform row; the "
                        + "pointer is not the place to do it, and a pointer that could would "
                        + "make the two levels indistinguishable at the point of resolution")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_policy_current_policy");
    }

    // -----------------------------------------------------------------------
    // The migration, against data that already holds the defect
    // -----------------------------------------------------------------------

    /**
     * V0088 refuses a database holding a cross-tenant cutover decision rather than
     * repairing it.
     *
     * <p>A migration that fails on real data is not a migration. This one is a
     * deliberate exception, and the reason is what the row is: a claim that
     * somebody authorised a transfer of ownership. Blanking the pointer or dropping
     * the row destroys the only evidence that the claim was ever made, in a table
     * V0024 made append-only so that it could be trusted. Refusing the deployment
     * puts it in front of a human with the rows still there.
     */
    @Test
    @DisplayName("V0088 refuses a database that already holds a cross-tenant cutover decision")
    void theMigrationRefusesAPopulatedDatabaseRatherThanRepairingIt() {
        try (TestDatabase.Handle older = TestDatabase.empty()) {
            DataSource dataSource = older.dataSource();
            Flyway.configure()
                    .dataSource(dataSource)
                    .target(BEFORE_THE_REPAIR)
                    .load()
                    .migrate();
            JdbcClient old = JdbcClient.create(dataSource);

            UUID tenantA = tenant(old, "pre-a");
            UUID tenantB = tenant(old, "pre-b");
            UUID scopeA = migrationScope(old, tenantA);
            UUID approvalB = approvalRequest(old, tenantB, approvalPolicy(old, tenantB), null);

            // The defect at rest. It has to succeed here, or the refusal below
            // proves nothing about what V0088 changed.
            UUID decision = cutoverDecision(old, tenantA, scopeA, approvalB, null);
            assertThat(decision).isNotNull();

            assertThatThrownBy(() ->
                            Flyway.configure().dataSource(dataSource).load().migrate())
                    .hasMessageContaining("cite an approval request belonging to another tenant");

            assertThat(old.sql("SELECT count(*) FROM migration.cutover_decisions WHERE id = :id")
                            .param("id", decision)
                            .query(Integer.class)
                            .single())
                    .as("the decision is still there to be adjudicated; a migration that "
                            + "quietly deleted it would be destroying the evidence of the claim")
                    .isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static UUID tenant(String slug) {
        return tenant(jdbc, slug);
    }

    private static UUID tenant(JdbcClient client, String slug) {
        UUID id = UUID.randomUUID();
        client.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug + "-" + id).update();
        return id;
    }

    /** A policy owned by {@code tenantId}, or by the platform when it is null. */
    private static UUID approvalPolicy(UUID tenantId) {
        return approvalPolicy(jdbc, tenantId);
    }

    private static UUID approvalPolicy(JdbcClient client, UUID tenantId) {
        UUID id = UUID.randomUUID();
        client.sql("""
                INSERT INTO audit.approval_policies (
                    id, tenant_id, action_code, scope_type, threshold_json,
                    required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, :actionCode, :scopeType, '{}'::jsonb,
                    'probe.capability', :from, 1, 'fixture')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("actionCode", "probe.action." + id)
                .param("scopeType", tenantId == null ? "PLATFORM" : "TENANT")
                .param("from", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .update();
        return id;
    }

    /** A request whose declared policy ownership matches the policy it names. */
    private static UUID approvalRequest(UUID tenantId, UUID policyId) {
        return approvalRequest(jdbc, tenantId, policyId, null);
    }

    /** A request declaring the ownership it is told to, truthfully or otherwise. */
    private static UUID approvalRequest(UUID tenantId, UUID policyId, boolean policyIsPlatform) {
        return approvalRequest(jdbc, tenantId, policyId, policyIsPlatform);
    }

    /**
     * @param policyIsPlatform what to declare; null means "declare what is true",
     *                         read back from the policy, which is what the service
     *                         does. Before V0088 the column did not exist and the
     *                         parameter is ignored, so the same helper serves the
     *                         pre-migration fixture.
     */
    private static UUID approvalRequest(JdbcClient client, UUID tenantId, UUID policyId, Boolean policyIsPlatform) {

        UUID id = UUID.randomUUID();
        if (!hasColumn(client, "audit", "approval_requests", "policy_is_platform")) {
            client.sql("""
                    INSERT INTO audit.approval_requests (
                        id, tenant_id, action_code, parameters_hash, scope_type, scope_id,
                        policy_id, policy_version, threshold_description, status, requested_by,
                        reason, expires_at)
                    VALUES (:id, :tenantId, :actionCode, :hash, :scopeType, :scopeId,
                        :policyId, 1, 'probe', 'PENDING', 'maker', 'probe', :expiresAt)
                    """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("actionCode", "probe.action." + id)
                    .param("hash", hash(id))
                    .param("scopeType", tenantId == null ? "PLATFORM" : "TENANT")
                    .param("scopeId", tenantId)
                    .param("policyId", policyId)
                    .param("expiresAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1))
                    .update();
            return id;
        }
        boolean declared = policyIsPlatform != null
                ? policyIsPlatform
                : isPlatformOwned(client, "audit.approval_policies", policyId);
        client.sql("""
                INSERT INTO audit.approval_requests (
                    id, tenant_id, action_code, parameters_hash, scope_type, scope_id,
                    policy_id, policy_is_platform, policy_version, threshold_description,
                    status, requested_by, reason, expires_at)
                VALUES (:id, :tenantId, :actionCode, :hash, :scopeType, :scopeId,
                    :policyId, :policyIsPlatform, 1, 'probe', 'PENDING', 'maker', 'probe',
                    :expiresAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("actionCode", "probe.action." + id)
                .param("hash", hash(id))
                .param("scopeType", tenantId == null ? "PLATFORM" : "TENANT")
                .param("scopeId", tenantId)
                .param("policyId", policyId)
                .param("policyIsPlatform", declared)
                .param("expiresAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1))
                .update();
        return id;
    }

    private static UUID migrationScope(UUID tenantId) {
        return migrationScope(jdbc, tenantId);
    }

    private static UUID migrationScope(JdbcClient client, UUID tenantId) {
        UUID programId = UUID.randomUUID();
        client.sql("""
                INSERT INTO migration.programs (
                    id, name, status, source_environment, target_environment, policy_version)
                VALUES (:id, :name, 'PLANNING', 'delever-prod', 'horecaos-prod', 1)
                """)
                .param("id", programId)
                .param("name", "probe-" + programId)
                .update();

        UUID scopeId = UUID.randomUUID();
        client.sql("""
                INSERT INTO migration.scopes (
                    id, program_id, tenant_id, capability, source_owner, target_owner, state)
                VALUES (:id, :programId, :tenantId, 'ORDERS', 'DELEVER', 'HORECAOS_ORDERING', 'CANARY')
                """)
                .param("id", scopeId)
                .param("programId", programId)
                .param("tenantId", tenantId)
                .update();
        return scopeId;
    }

    private static UUID cutoverDecision(
            UUID tenantId, UUID scopeId, UUID approvalRequestId, Boolean approvalIsPlatform) {
        return cutoverDecision(jdbc, tenantId, scopeId, approvalRequestId, approvalIsPlatform);
    }

    /**
     * @param approvalIsPlatform what to declare. Null leaves it undeclared, which
     *                           is the shape the pre-V0088 schema had no column
     *                           for and the shape
     *                           {@code ck_cutover_approval_ownership_declared}
     *                           refuses afterwards.
     */
    private static UUID cutoverDecision(
            JdbcClient client, UUID tenantId, UUID scopeId, UUID approvalRequestId, Boolean approvalIsPlatform) {

        UUID id = UUID.randomUUID();
        boolean declares = hasColumn(client, "migration", "cutover_decisions", "approval_request_is_platform");
        client.sql("""
                INSERT INTO migration.cutover_decisions (
                    id, tenant_id, scope_id, from_state, to_state, scope_version, decision,
                    reason, evidence_snapshot, requested_by, decided_by, approval_request_id,
                    %s idempotency_key, requested_at, decided_at)
                VALUES (:id, :tenantId, :scopeId, 'CANARY', 'CUTOVER_READY', 1, 'APPROVED',
                    'probe', '{}'::jsonb, 'maker', 'checker', :approvalRequestId,
                    %s :idempotencyKey, :now, :now)
                """.formatted(
                        declares ? "approval_request_is_platform," : "", declares ? ":approvalIsPlatform," : ""))
                .param("id", id)
                .param("tenantId", tenantId)
                .param("scopeId", scopeId)
                .param("approvalRequestId", approvalRequestId)
                .params(
                        declares
                                ? java.util.Collections.singletonMap("approvalIsPlatform", approvalIsPlatform)
                                : java.util.Map.<String, Object>of())
                .param("idempotencyKey", "probe-" + id)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return id;
    }

    /** A region owned by {@code tenantId}, or a platform region when it is null. */
    private static UUID region(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.regions (
                    id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                    centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon)
                VALUES (:id, :tenantId, :code, 'RU', 'UZ', 'EN',
                    41.31, 69.24, 41.0, 69.0, 41.6, 69.5)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param(
                        "code",
                        "R" + id.toString().replace("-", "").substring(0, 20).toUpperCase())
                .update();
        return id;
    }

    private static UUID serviceZone(UUID tenantId) {
        UUID brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param(
                        "code",
                        "B"
                                + brandId.toString()
                                        .replace("-", "")
                                        .substring(0, 8)
                                        .toUpperCase())
                .param("slug", "brand-" + brandId)
                .update();

        UUID zoneId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.service_zones (
                    id, tenant_id, brand_id, zone_role, code,
                    display_name_ru, display_name_uz, display_name_en)
                VALUES (:id, :tenantId, :brandId, 'DELIVERY', :code, 'RU', 'UZ', 'EN')
                """)
                .param("id", zoneId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param(
                        "code",
                        "Z" + zoneId.toString().replace("-", "").substring(0, 8).toUpperCase())
                .update();
        return zoneId;
    }

    private static UUID zoneVersion(UUID tenantId, UUID zoneId, int version, UUID regionId, Boolean regionIsPlatform) {

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.service_zone_versions (
                    id, tenant_id, zone_id, zone_role, version, status, area, authoring_shape,
                    region_id, region_is_platform, priority, area_sq_meters, currency, created_by)
                VALUES (:id, :tenantId, :zoneId, 'DELIVERY', :version, 'DRAFT',
                    ST_Multi(ST_Buffer(
                        ST_SetSRID(ST_MakePoint(69.24, 41.31), 4326)::geography,
                        1000)::geometry)::geography,
                    '{}'::jsonb, :regionId, :regionIsPlatform, 0, 1000.0, 'UZS', :createdBy)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("zoneId", zoneId)
                .param("version", version)
                .param("regionId", regionId)
                .param("regionIsPlatform", regionIsPlatform)
                .param("createdBy", UUID.randomUUID())
                .update();
        return id;
    }

    /** A policy owned by {@code tenantId}, or the platform default when it is null. */
    private static UUID policy(UUID tenantId, String keyCode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.policies (
                    id, key_code, scope_type, tenant_id, version, status, document,
                    document_hash, valid_from, created_by)
                VALUES (:id, :keyCode, :scopeType, :tenantId, 1, 'ACTIVE', '{}'::jsonb,
                    :hash, :from, 'fixture')
                """)
                .param("id", id)
                .param("keyCode", keyCode)
                .param("scopeType", tenantId == null ? "PLATFORM" : "TENANT")
                .param("tenantId", tenantId)
                .param("hash", hash(id))
                .param("from", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .update();
        return id;
    }

    private static void policyCurrent(UUID tenantId, String keyCode, UUID policyId) {
        jdbc.sql("""
                INSERT INTO tenant.policy_current (
                    key_code, scope_type, tenant_id, policy_id, policy_version, activated_by)
                VALUES (:keyCode, :scopeType, :tenantId, :policyId, 1, 'fixture')
                """)
                .param("keyCode", keyCode)
                .param("scopeType", tenantId == null ? "PLATFORM" : "TENANT")
                .param("tenantId", tenantId)
                .param("policyId", policyId)
                .update();
    }

    private static boolean isPlatformOwned(JdbcClient client, String table, UUID id) {
        return Boolean.TRUE.equals(client.sql("SELECT tenant_id IS NULL FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single());
    }

    /**
     * Whether the schema in front of us has that column yet.
     *
     * <p>The pre-V0088 fixture and the migrated one share these helpers, and the
     * alternative — a second set of inserts differing by two columns — is how the
     * two drift into testing different rows.
     */
    private static boolean hasColumn(JdbcClient client, String schema, String table, String column) {
        return client.sql("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = :schema AND table_name = :table AND column_name = :column
                """)
                        .param("schema", schema)
                        .param("table", table)
                        .param("column", column)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** A 64-character lowercase hex string, which is all the CHECK asks of it. */
    private static String hash(UUID seed) {
        return (seed.toString().replace("-", "") + seed.toString().replace("-", ""))
                .toLowerCase()
                .substring(0, 64);
    }
}
