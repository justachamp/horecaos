package uz.horecaos.platform.courier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The evidence media reference on a courier engagement belongs to the tenant
 * that cites it (ADR 0010, ADR 0029, V0069).
 *
 * <p>{@code evidence_media_id} points at the scan of a courier's self-employment
 * registration certificate: a named person's tax document, held with PRIVATE
 * visibility. Until V0069 it referenced {@code media.assets (asset_id)} with a
 * single column, sitting directly beside a composite reference to the courier,
 * and nothing in Java looked at it either — the controller took it from the
 * request body and the {@code UPDATE} applied its tenant predicate to the
 * engagement row and never to the asset. Two things followed, and both are
 * asserted below: a tenant could durably store a pointer into another tenant's
 * private evidence, and the endpoint answered "does this media asset id exist
 * anywhere on HorecaOS" for any uuid a caller cared to submit.
 *
 * <p>The two halves of the fix are tested separately because they fail
 * separately. The service check is what a caller meets: a clean 400 with one
 * answer for both refusals. The constraint is what holds when nothing comes
 * through the service — a repair script, a background job, a second write path
 * somebody adds next year — so it is exercised by writing the row directly.
 *
 * <p>The availability port is a double here rather than the real
 * {@code MediaAssetService}, and it answers from {@code media.assets} with the
 * tenant predicate so the question being asked is a real one. That double is not
 * what proves the isolation; the database is, in
 * {@link #theDatabaseRefusesTheCrossTenantRowWhenTheServiceIsBypassed()}.
 */
class CourierEvidenceMediaTenantScopeTests {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");

    /** A Tuesday, 12:00 in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");

    private static TestDatabase.Handle db;

    private DriverManagerDataSource dataSource;
    private JdbcClient jdbc;
    private JdbcCourierStore courierStore;
    private RecordingAudit audit;
    private CourierEngagementService engagements;

    /** Tenant A's private registration scan. Tenant B must never cite it. */
    private UUID assetOfA;
    /** Tenant B's own private registration scan. */
    private UUID assetOfB;

    private UUID engagementOfB;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for courier evidence media tests");
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
        dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("""
                TRUNCATE TABLE fulfillment.courier_engagement_evidence_orphans,
                    fulfillment.courier_engagements,
                    fulfillment.couriers,
                    fulfillment.courier_types,
                    media.assets CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        audit = new RecordingAudit();
        courierStore = new JdbcCourierStore(jdbc);
        engagements = new CourierEngagementService(courierStore, new ReversibleProtection(), audit,
                new CourierPolicyResolver(new NoPolicies()),
                new AssetsTableAvailability(jdbc),
                Clock.fixed(NOON, ZoneOffset.UTC));

        seedTenant(TENANT_A, "tenant-a");
        seedTenant(TENANT_B, "tenant-b");
        assetOfA = seedPrivateScan(TENANT_A);
        assetOfB = seedPrivateScan(TENANT_B);

        UUID typeOfB = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(typeOfB, TENANT_B, "SCOOTER", "Scooter",
                "SCOOTER", 0, 15_000, 2, 60, "ACTIVE"));
        engagementOfB = engagements.register(new CourierEngagementService.NewCourier(
                        TENANT_B, typeOfB, "keycloak-courier-b", "K-001", "Alisher Karimov",
                        today(), manager(), "onboarding a rider", "corr"))
                .engagementId();

        // The opening fact belongs to the seed, not to the test. Cleared here so
        // "what did verify record" is answered by the list itself.
        audit.facts.clear();
    }

    // -------------------------------------------------------------- the service

    @Test
    @DisplayName("a tenant citing another tenant's private scan is refused cleanly, not by a "
            + "constraint violation")
    void theCrossTenantVerifyIsRefusedCleanly() {
        Throwable refused = catchThrowable(() -> engagements.verify(verifyWith(assetOfA)));

        assertThat(refused)
                .as("a 500 carrying a constraint name is not a refusal a client can act on")
                .isInstanceOf(ApiException.class);
        ErrorCode code = ((ApiException) refused).errorCode();
        assertThat(code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code.status().is4xxClientError()).isTrue();

        // Nothing was written. The engagement is still awaiting verification and
        // holds no pointer into tenant A's evidence.
        EngagementRow after = courierStore.findEngagement(TENANT_B, engagementOfB).orElseThrow();
        assertThat(after.evidenceMediaId()).isNull();
        assertThat(after.registrationVerifiedAt()).isNull();
        assertThat(audit.facts).isEmpty();
    }

    @Test
    @DisplayName("the refusal does not distinguish another tenant's asset from an id that exists "
            + "nowhere, so the endpoint is not an existence oracle")
    void theRefusalDoesNotRevealWhetherTheAssetExists() {
        UUID neverAllocated = UUID.fromString("99999999-9999-9999-9999-999999999999");
        assertThat(jdbc.sql("SELECT count(*) FROM media.assets WHERE asset_id = :id")
                .param("id", neverAllocated).query(Long.class).single())
                .as("the fabricated id must genuinely exist nowhere for this test to mean anything")
                .isZero();

        Throwable foreignThrown = catchThrowable(() -> engagements.verify(verifyWith(assetOfA)));
        Throwable fabricatedThrown =
                catchThrowable(() -> engagements.verify(verifyWith(neverAllocated)));
        assertThat(foreignThrown).isInstanceOf(ApiException.class);
        assertThat(fabricatedThrown).isInstanceOf(ApiException.class);

        ApiException foreign = (ApiException) foreignThrown;
        ApiException fabricated = (ApiException) fabricatedThrown;

        assertThat(fabricated.errorCode()).isEqualTo(foreign.errorCode());
        assertThat(fabricated.getMessage())
                .as("two different messages are two different answers, and the difference between "
                        + "them is the whole disclosure")
                .isEqualTo(foreign.getMessage());
        assertThat(foreign.getMessage())
                .as("the message must not name the tenant that does hold the asset")
                .doesNotContain(TENANT_A.toString())
                .doesNotContain(assetOfA.toString());
    }

    @Test
    @DisplayName("the tenant's own scan still verifies, and reaches the engagement and the audit "
            + "evidence field")
    void theSameTenantVerifyStillWorks() {
        EngagementRow verified = engagements.verify(verifyWith(assetOfB));

        assertThat(verified.evidenceMediaId()).isEqualTo(assetOfB);
        assertThat(verified.registrationVerifiedAt()).isEqualTo(NOON);
        assertThat(audit.facts).singleElement()
                .satisfies(fact -> assertThat(fact.evidenceReference()).isEqualTo(assetOfB.toString()));
    }

    @Test
    @DisplayName("evidence stays optional: an attestation with no scan is still accepted")
    void anAttestationWithNoScanIsAccepted() {
        EngagementRow verified = engagements.verify(verifyWith(null));

        assertThat(verified.evidenceMediaId()).isNull();
        assertThat(verified.status().name()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------- the database

    @Test
    @DisplayName("the database refuses the cross-tenant row even when the service check is bypassed")
    void theDatabaseRefusesTheCrossTenantRowWhenTheServiceIsBypassed() {
        Throwable refused = catchThrowable(() -> jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET evidence_media_id = :assetId
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("assetId", assetOfA).param("tenantId", TENANT_B).param("id", engagementOfB)
                .update());

        assertThat(refused).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(refused).hasMessageContaining("fk_engagement_evidence");

        // And the tenant's own asset is still accepted through the same statement,
        // so what was refused was the tenant boundary and not the column.
        assertThat(jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET evidence_media_id = :assetId
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("assetId", assetOfB).param("tenantId", TENANT_B).param("id", engagementOfB)
                .update()).isEqualTo(1);
    }

    @Test
    @DisplayName("the reference names both columns of media.assets' tenant-scoped key")
    void theConstraintIsComposite() {
        String definition = jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'fk_engagement_evidence'
                   AND conrelid = 'fulfillment.courier_engagements'::regclass
                """).query(String.class).single();

        assertThat(definition).isEqualTo(
                "FOREIGN KEY (evidence_media_id, tenant_id) REFERENCES media.assets(asset_id, tenant_id)");
    }

    // ------------------------------------------------------------ the migration

    @Test
    @DisplayName("V0069 applies to a database that already holds engagements, and quarantines the "
            + "unsatisfiable pointer rather than discarding it")
    void theMigrationAppliesToADatabaseThatAlreadyHoldsEngagements() {
        // A separate database, migrated only as far as the release before this
        // one, so the rows below can be written under the constraint as it was.
        //
        // From the shared holder rather than a CREATE DATABASE issued through
        // this suite's connection: the name was already unique, but nothing ever
        // dropped it, and a database that nothing drops used to be reaped with
        // the container. The holder drops it when the handle closes.
        try (TestDatabase.Handle legacyDb = TestDatabase.empty()) {
            DataSource legacySource = legacyDb.dataSource();
            // 0067 rather than "the one before mine", because V0068 belongs to
            // another change in flight and this test must not depend on whether it
            // has landed. Everything V0069 touches exists by 0067.
            Flyway.configure().dataSource(legacySource)
                    .target(MigrationVersion.fromVersion("0067"))
                    .load().migrate();

            JdbcClient old = JdbcClient.create(legacySource);
            assertThat(old.sql("""
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                     WHERE conname = 'fk_engagement_evidence'
                    """).query(String.class).single())
                    .as("the starting point must be the defective single-column reference")
                    .isEqualTo("FOREIGN KEY (evidence_media_id) REFERENCES media.assets(asset_id)");

            seedTenant(old, TENANT_A, "tenant-a");
            seedTenant(old, TENANT_B, "tenant-b");
            UUID scanOfA = seedPrivateScan(old, TENANT_A);
            UUID scanOfB = seedPrivateScan(old, TENANT_B);

            UUID crossTenant = seedEngagement(old, TENANT_B, "K-B", scanOfA);
            UUID conforming = seedEngagement(old, TENANT_A, "K-A", scanOfA);
            UUID noEvidence = seedEngagement(old, TENANT_A, "K-A2", null);

            Flyway.configure().dataSource(legacySource).load().migrate();

            assertThat(evidenceOf(old, crossTenant))
                    .as("the pointer into another tenant's evidence is gone from the engagement")
                    .isNull();
            assertThat(evidenceOf(old, conforming))
                    .as("a reference that already satisfied the tenant key is untouched")
                    .isEqualTo(scanOfA);
            assertThat(evidenceOf(old, noEvidence)).isNull();

            assertThat(old.sql("""
                    SELECT evidence_media_id FROM fulfillment.courier_engagement_evidence_orphans
                     WHERE tenant_id = :tenantId AND engagement_id = :id
                    """).param("tenantId", TENANT_B).param("id", crossTenant)
                    .query(UUID.class).list())
                    .as("a migration that silently discards a row is worse than one that fails; the "
                            + "pointer somebody once attached is the only record that they did")
                    .containsExactly(scanOfA);

            assertThat(old.sql("SELECT count(*) FROM fulfillment.courier_engagement_evidence_orphans")
                    .query(Long.class).single())
                    .as("only the unsatisfiable row is quarantined")
                    .isEqualTo(1L);

            assertThat(scanOfB).isNotNull();
        }
    }

    // ------------------------------------------------------------------ helpers

    private CourierEngagementService.VerifyRegistration verifyWith(UUID evidenceMediaId) {
        return new CourierEngagementService.VerifyRegistration(TENANT_B, engagementOfB,
                "312345678901", today().plusYears(1), VerificationMethod.MANUAL_ATTESTATION,
                evidenceMediaId, manager(), "sighted the registration certificate", "corr");
    }

    private LocalDate today() {
        return LocalDate.ofInstant(NOON, ZoneOffset.UTC);
    }

    private void seedTenant(UUID tenantId, String slug) {
        seedTenant(jdbc, tenantId, slug);
    }

    private static void seedTenant(JdbcClient client, UUID tenantId, String slug) {
        client.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status)
                VALUES (:id, :slug, :slug, :slug, 'UZS', 'Asia/Tashkent', 'ACTIVE')
                """).param("id", tenantId).param("slug", slug).update();
    }

    private UUID seedPrivateScan(UUID tenantId) {
        return seedPrivateScan(jdbc, tenantId);
    }

    /**
     * A finalized, verified, PRIVATE asset — what a registration certificate scan
     * actually is. AVAILABLE matters: the check under test asks whether the asset
     * is the caller's own <em>and</em> verified.
     */
    private static UUID seedPrivateScan(JdbcClient client, UUID tenantId) {
        UUID assetId = UUID.randomUUID();
        client.sql("""
                INSERT INTO media.assets (asset_id, tenant_id, owner_scope, owner_id, bucket,
                    object_key, visibility, status, declared_content_type, declared_size_bytes,
                    verified_content_type, verified_size_bytes, verified_checksum_sha256)
                VALUES (:assetId, :tenantId, 'TENANT', :tenantId, 'horecaos-media',
                    :objectKey, 'PRIVATE', 'AVAILABLE', 'image/jpeg', 1024,
                    'image/jpeg', 1024, repeat('0', 64))
                """)
                .param("assetId", assetId).param("tenantId", tenantId)
                .param("objectKey", tenantId + "/tenant/" + tenantId + "/" + assetId)
                .update();
        return assetId;
    }

    /** Written with SQL rather than the service, because the point is the row. */
    private static UUID seedEngagement(JdbcClient client, UUID tenantId, String reference,
            UUID evidenceMediaId) {

        UUID typeId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID engagementId = UUID.randomUUID();
        client.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class)
                VALUES (:id, :tenantId, :code, 'Scooter', 'SCOOTER')
                """).param("id", typeId).param("tenantId", tenantId)
                .param("code", reference.replace('-', '_')).update();
        client.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'protected', 'ACTIVE')
                """).param("id", courierId).param("tenantId", tenantId).param("typeId", typeId)
                .param("subject", "keycloak-" + reference).param("reference", reference).update();
        client.sql("""
                INSERT INTO fulfillment.courier_engagements (id, tenant_id, courier_id,
                    engagement_type, status, engaged_from, evidence_media_id)
                VALUES (:id, :tenantId, :courierId, 'SELF_EMPLOYED', 'PENDING_VERIFICATION',
                    DATE '2026-01-01', :evidence)
                """).param("id", engagementId).param("tenantId", tenantId)
                .param("courierId", courierId).param("evidence", evidenceMediaId).update();
        return engagementId;
    }

    private static UUID evidenceOf(JdbcClient client, UUID engagementId) {
        return client.sql("""
                SELECT evidence_media_id FROM fulfillment.courier_engagements WHERE id = :id
                """).param("id", engagementId).query(UUID.class).optional().orElse(null);
    }

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Branch manager");
    }

    /**
     * Answers the availability question from {@code media.assets} itself, with
     * the tenant predicate and the verified status the real service applies.
     *
     * <p>A stand-in for {@code MediaAssetService}, which lives in a module this
     * suite does not construct. It is deliberately a query rather than a map of
     * expected answers: a double that returns what the test already decided would
     * pass whether or not the service asks the tenant-scoped question at all.
     */
    private record AssetsTableAvailability(JdbcClient jdbc) implements MediaAvailability {

        @Override
        public boolean allDisplayable(UUID tenantId, Set<MediaAssetId> assetIds) {
            for (MediaAssetId assetId : assetIds) {
                Long found = jdbc.sql("""
                        SELECT count(*) FROM media.assets
                         WHERE tenant_id = :tenantId AND asset_id = :assetId
                           AND status = 'AVAILABLE'
                        """)
                        .param("tenantId", tenantId).param("assetId", assetId.value())
                        .query(Long.class).single();
                if (found == 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Nothing configured, so ADR 0042's provisional defaults apply. */
    private static final class NoPolicies implements PolicyResolver {

        @Override
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            return Optional.empty();
        }

        @Override
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId,
                int policyVersion) {
            return Optional.empty();
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }

    private static final class ReversibleProtection implements FieldProtection {

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record,
                String plaintext) {
            byte[] reversed = new StringBuilder(plaintext).reverse().toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new ProtectedValue("test-key", "TEST", new byte[] {1}, reversed, 1);
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            return new StringBuilder(new String(value.ciphertext(),
                    java.nio.charset.StandardCharsets.UTF_8)).reverse().toString();
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return Integer.toHexString((tenantId + lookupDomain + normalizedValue).hashCode());
        }
    }
}
