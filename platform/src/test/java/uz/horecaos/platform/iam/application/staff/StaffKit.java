package uz.horecaos.platform.iam.application.staff;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.application.GrantAuditListener;
import uz.horecaos.platform.audit.application.StaffMemberAuditListener;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.GrantChanged;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.TenantAvailability;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.grants.GrantAuthority;
import uz.horecaos.platform.iam.api.staff.StaffMemberChanged;
import uz.horecaos.platform.iam.api.staff.StaffPhotos;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffEmergencyContactStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * The staff member record wired the way {@code GrantManagementServiceTests} wires
 * grants: plain {@code new}, a real PostgreSQL, the real envelope protection on a
 * fixed key, and the real audit recorder -- and no Spring proxy, so every
 * transaction a test relies on is one it opens itself.
 *
 * <p>Events are routed to the real {@link StaffMemberAuditListener} and {@link
 * GrantAuditListener} <em>synchronously, inside the transaction that published
 * them</em>, which is what {@code BEFORE_COMMIT} does in the running platform. A
 * test that wants "the audit fact for this edit" reads {@code
 * audit.audit_events} afterwards and gets the row the platform would have
 * written, redaction and all.
 */
final class StaffKit {

    static final UUID TENANT_A = UUID.fromString("018fa000-1000-7000-8000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("018fa000-1000-7000-8000-0000000000b1");
    static final UUID BRAND_1 = UUID.fromString("018fa000-1000-7000-8000-0000000001a1");
    static final UUID BRAND_2 = UUID.fromString("018fa000-1000-7000-8000-0000000001a2");
    static final UUID LOCATION_1 = UUID.fromString("018fa000-1000-7000-8000-0000000002a1");
    static final UUID LOCATION_2 = UUID.fromString("018fa000-1000-7000-8000-0000000002a2");
    static final UUID LOCATION_3 = UUID.fromString("018fa000-1000-7000-8000-0000000002a3");
    static final UUID B_BRAND = UUID.fromString("018fa000-1000-7000-8000-0000000001b1");
    static final UUID B_LOCATION = UUID.fromString("018fa000-1000-7000-8000-0000000002b1");

    static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    final DataSource dataSource;
    final JdbcClient jdbc;
    final Clock clock;
    final JdbcStaffMemberStore store;
    final JdbcStaffEmergencyContactStore contactStore;
    final StaffMemberCodec codec;
    final StaffNameCache cache;
    final TransactionTemplate tx;
    final JdbcAuthorizationService authorization;
    final GrantManagementService grants;
    final FakePhotos photos;
    final FakeAccounts accounts = new FakeAccounts();
    final StaffMemberService members;
    final StaffEmergencyContactService emergency;
    final StaffDirectoryService directory;
    final StaffMemberCardService cards;

    /** Every StaffMemberChanged the services published, in order, for assertions on what an event may carry. */
    final List<StaffMemberChanged> published = new ArrayList<>();

    /** Lets a test make the Nth revoke fail, to leave a person half ended. */
    final FailingRevoke revoking;

    StaffKit(TestDatabase.Handle db) {
        dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        jdbc = JdbcClient.create(dataSource);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        photos = new FakePhotos(jdbc);

        authorization =
                new JdbcAuthorizationService(
                        jdbc,
                        clock,
                        () -> new AuthenticatedActor("no-request-actor-in-fixture", Set.of(), Map.of()),
                        tenantId -> TenantAvailability.OPERATING) {
                    @Override
                    public void evictGrants(String subject, @Nullable UUID tenantId) {
                        // no cache in this fixture
                    }
                };

        JdbcAuditRecorder recorder =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        GrantAuditListener grantAudit = new GrantAuditListener(recorder);
        StaffMemberAuditListener staffAudit = new StaffMemberAuditListener(recorder);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof GrantChanged change) {
                grantAudit.onGrantChanged(change);
            } else if (event instanceof StaffMemberChanged change) {
                published.add(change);
                staffAudit.onStaffMemberChanged(change);
            }
        };

        grants = new GrantManagementService(jdbc, authorization, authorization, publisher, clock);
        revoking = new FailingRevoke(grants);

        store = new JdbcStaffMemberStore(jdbc);
        contactStore = new JdbcStaffEmergencyContactStore(jdbc);
        codec = new StaffMemberCodec(TestProtection.envelope());
        ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager(CacheRegistry.STAFF_DISPLAY_NAMES.cacheName());
        cache = new StaffNameCache(caches);
        members = new StaffMemberService(store, codec, cache, publisher, authorization, revoking, photos, tx, clock);
        emergency = new StaffEmergencyContactService(store, contactStore, TestProtection.envelope(), publisher, clock);
        directory = new StaffDirectoryService(store, codec, cache, accounts);
        cards = new StaffMemberCardService(store, codec);
    }

    // ------------------------------------------------------------------ fixtures

    private boolean rolesSynchronised;

    /**
     * Empties everything a test of the record touches and seeds two tenants with
     * their branches.
     *
     * <p>Deletes the fixture tenants by id instead of {@code TRUNCATE tenant.tenants
     * CASCADE}, on purpose: that statement cascades into {@code iam.roles} (tenant-defined
     * roles reference a tenant) and empties the platform-defined rows with it, which
     * then costs {@code RoleRegistrySynchronizer} some four hundred single statements
     * over a connection-per-statement data source -- seconds, per test. The registry is
     * not something a test changes, so it is written once.
     */
    void reset() {
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE iam.staff_members, iam.staff_member_counters, media.assets CASCADE")
                .update();
        for (UUID tenantId : new UUID[] {TENANT_A, TENANT_B}) {
            jdbc.sql("DELETE FROM tenant.locations WHERE tenant_id = :t")
                    .param("t", tenantId)
                    .update();
            jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t")
                    .param("t", tenantId)
                    .update();
            jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t")
                    .param("t", tenantId)
                    .update();
        }
        published.clear();
        accounts.clear();
        photos.clear();
        revoking.failOn = 0;
        if (!rolesSynchronised) {
            new RoleRegistrySynchronizer(jdbc).synchronize();
            rolesSynchronised = true;
        }

        seedTenant(TENANT_A, "staff-kit-a", "Asia/Tashkent");
        seedBrand(TENANT_A, BRAND_1, "B1");
        seedBrand(TENANT_A, BRAND_2, "B2");
        seedLocation(TENANT_A, BRAND_1, LOCATION_1, "L1");
        seedLocation(TENANT_A, BRAND_1, LOCATION_2, "L2");
        seedLocation(TENANT_A, BRAND_2, LOCATION_3, "L3");

        seedTenant(TENANT_B, "staff-kit-b", "Asia/Tashkent");
        seedBrand(TENANT_B, B_BRAND, "BB");
        seedLocation(TENANT_B, B_BRAND, B_LOCATION, "BL");
    }

    void seedTenant(UUID tenantId, String slug, String zone) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', :zone, 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", slug)
                .param("zone", zone)
                .update();
    }

    void seedBrand(UUID tenantId, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("t", tenantId)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    void seedLocation(UUID tenantId, UUID brandId, UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    /** A grant written straight into the table, the way the other suites seed one. */
    UUID grant(UUID tenantId, String subject, PlatformRole role, String scopeType, UUID scopeId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :t, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    /** Registers an invited member inside a transaction, as the invitation flow does. */
    UUID invite(UUID tenantId, String subject, String first, @Nullable String last, @Nullable String phone) {
        tx.executeWithoutResult(status ->
                members.registerInvited(tenantId, subject, first, last, phone, "inviting-manager", "corr-" + subject));
        return store.findBySubject(tenantId, subject).orElseThrow().id();
    }

    /** A member who has accepted: registered, then activated with the typed name. */
    UUID activeMember(UUID tenantId, String subject, String first, @Nullable String last, @Nullable String phone) {
        UUID id = invite(tenantId, subject, first, last, phone);
        tx.executeWithoutResult(status -> members.activate(tenantId, subject, first, last, "corr-accept-" + subject));
        return id;
    }

    <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    // ------------------------------------------------------------------- doubles

    /**
     * A {@link StaffPhotos} whose assets are real {@code media.assets} rows, so the
     * composite reference from the staff member holds them to the same rule it
     * holds a production asset to; private, tenant-owned and verified unless a
     * test says otherwise.
     */
    static final class FakePhotos implements StaffPhotos {

        private final JdbcClient jdbc;
        private final Map<UUID, UUID> tenantOf = new HashMap<>();
        private final Set<UUID> notPrivate = new java.util.HashSet<>();

        FakePhotos(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        void clear() {
            tenantOf.clear();
            notPrivate.clear();
        }

        void markPublic(UUID assetId) {
            notPrivate.add(assetId);
        }

        @Override
        public Ingested ingest(
                UUID tenantId, byte[] content, @Nullable String originalFilename, @Nullable UUID actorId) {
            if (content.length > 0 && content[0] == 'X') {
                return new Ingested(false, null, "CONTENT_NOT_AN_IMAGE");
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO media.assets (asset_id, tenant_id, owner_scope, owner_id, bucket,
                        object_key, visibility, status, declared_content_type, declared_size_bytes,
                        verified_content_type, verified_size_bytes, verified_checksum_sha256)
                    VALUES (:id, :t, 'TENANT', :t, 'horecaos-media', :key, 'PRIVATE', 'AVAILABLE',
                        'image/jpeg', 10, 'image/jpeg', 10, repeat('0', 64))
                    """)
                    .param("id", id)
                    .param("t", tenantId)
                    .param("key", tenantId + "/tenant/" + id)
                    .update();
            tenantOf.put(id, tenantId);
            return new Ingested(true, id, null);
        }

        @Override
        public boolean isPrivateTenantAsset(UUID tenantId, UUID assetId) {
            return tenantId.equals(tenantOf.get(assetId)) && !notPrivate.contains(assetId);
        }

        @Override
        public Optional<URI> signedReadUrl(UUID tenantId, UUID assetId) {
            return isPrivateTenantAsset(tenantId, assetId)
                    ? Optional.of(URI.create("https://signed.example/" + assetId + "?expires=300"))
                    : Optional.empty();
        }
    }

    /** What the identity provider "holds", for the backfill and the directory's rollout fallback. */
    static final class FakeAccounts implements StaffAccounts {

        final Map<String, StaffProfile> profiles = new HashMap<>();
        final Set<String> unreachable = new java.util.HashSet<>();

        void clear() {
            profiles.clear();
            unreachable.clear();
        }

        @Override
        public Optional<StaffProfile> profile(String subjectId) {
            if (unreachable.contains(subjectId)) {
                throw new IllegalStateException("Keycloak is unreachable");
            }
            return Optional.ofNullable(profiles.get(subjectId));
        }

        @Override
        public Optional<String> displayName(String subjectId) {
            if (unreachable.contains(subjectId)) {
                throw new IllegalStateException("Keycloak is unreachable");
            }
            StaffProfile profile = profiles.get(subjectId);
            if (profile == null) {
                return Optional.empty();
            }
            String name = ((profile.firstName() == null ? "" : profile.firstName()) + " "
                            + (profile.lastName() == null ? "" : profile.lastName()))
                    .strip();
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            return Optional.empty();
        }

        @Override
        public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
            return Optional.empty();
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {}

        @Override
        public void setPassword(String subjectId, String password) {}

        @Override
        public void logoutEverywhere(String subjectId) {}
    }

    /** Delegates to the real grant authority and fails the Nth revoke on request. */
    static final class FailingRevoke implements GrantAuthority {

        private final GrantManagementService delegate;

        /** 1-based: the revoke that throws; 0 for none. */
        int failOn;

        private int calls;

        FailingRevoke(GrantManagementService delegate) {
            this.delegate = delegate;
        }

        void reset() {
            calls = 0;
        }

        @Override
        public UUID grant(
                String principalSubject,
                String roleCode,
                uz.horecaos.platform.iam.api.ResourceScope scope,
                String reason,
                @Nullable Instant validUntil,
                String granterSubject) {
            return delegate.grant(principalSubject, roleCode, scope, reason, validUntil, granterSubject);
        }

        @Override
        public boolean revoke(@Nullable UUID tenantId, UUID grantId, String revokerSubject, String reason) {
            calls++;
            if (failOn != 0 && calls == failOn) {
                throw new IllegalStateException("the grant store went away mid-way");
            }
            return delegate.revoke(tenantId, grantId, revokerSubject, reason);
        }
    }
}
