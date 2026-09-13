package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBranchTagStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBranchTagStore.BranchTag;
import uz.horecaos.platform.web.api.ApiException;

/**
 * 10.10d against PostgreSQL: a chain's own branch tag registry
 * ({@code tenant.branch_tags}, V0256) and which branch carries which tag
 * ({@code tenant.location_branch_tags}, V0257) — no table, no endpoint, no
 * screen before this wave.
 */
class BranchTagServiceTests {

    private static final UUID TENANT = UUID.fromString("018f7b20-1000-7000-8000-0000000000e1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f7b20-1000-7000-8000-0000000000e2");
    private static final UUID BRAND = UUID.fromString("018f7b20-1000-7000-8000-0000000000e3");
    private static final UUID LOCATION_A = UUID.fromString("018f7b20-1000-7000-8000-0000000000e4");
    private static final UUID LOCATION_B = UUID.fromString("018f7b20-1000-7000-8000-0000000000e5");

    private static final ActorRef ACTOR = ActorRef.user("chain-admin-1", null);

    /** This suite is about the registry and assignment set, not the audit trail. */
    private static final AuditRecorder NO_OP_AUDIT = fact -> {};

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private BranchTagService tags;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenancy();

        Clock clock = Clock.fixed(Instant.parse("2026-09-12T05:00:00Z"), ZoneOffset.UTC);
        tags = new BranchTagService(new JdbcBranchTagStore(jdbc), NO_OP_AUDIT, clock);
    }

    @Test
    void aChainRegistersAndListsItsOwnTags() {
        UUID airport = tags.create(TENANT, "airport", "Аэропорт", ACTOR, "near the airport");
        UUID open247 = tags.create(TENANT, "open-247", "24/7", ACTOR, "never closes");

        List<BranchTag> registry = tags.list(TENANT, true);

        assertThat(registry).extracting(BranchTag::id).containsExactlyInAnyOrder(airport, open247);
        assertThat(registry).extracting(BranchTag::status).containsOnly("ACTIVE");
    }

    @Test
    void twoTagsInOneTenantCannotShareACode() {
        tags.create(TENANT, "airport", "Аэропорт", ACTOR, "first");

        assertThatThrownBy(() -> tags.create(TENANT, "airport", "Duplicate", ACTOR, "second"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void twoTenantsMaySeparatelyUseTheSameCode() {
        tags.create(TENANT, "airport", "Аэропорт", ACTOR, "ours");

        assertThatThrownBy(() -> tags.create(TENANT, "airport", "Аэропорт", ACTOR, "also ours"))
                .isInstanceOf(ApiException.class);

        // A sibling tenant is not blocked by this tenant's code.
        UUID otherTagId = tags.create(OTHER_TENANT, "airport", "Airport", ACTOR, "unrelated chain");
        assertThat(tags.list(OTHER_TENANT, true)).extracting(BranchTag::id).containsExactly(otherTagId);
        assertThat(tags.list(TENANT, true))
                .as("tenant isolation: a sibling's registry never appears in this tenant's list")
                .extracting(BranchTag::id)
                .doesNotContain(otherTagId);
    }

    @Test
    void archivingATagHidesItFromTheActiveListButAnAlreadyTaggedBranchKeepsResolvingIt() {
        UUID airport = tags.create(TENANT, "airport", "Аэропорт", ACTOR, "near the airport");
        tags.setTagsForLocation(TENANT, BRAND, LOCATION_A, List.of(airport), ACTOR, "tag this branch");

        tags.archive(TENANT, airport, ACTOR, "airport branch closed");

        assertThat(tags.list(TENANT, true))
                .as("an archived tag drops out of the active registry")
                .isEmpty();
        assertThat(tags.list(TENANT, false))
                .as("but the archived row itself survives, for history")
                .extracting(BranchTag::id)
                .containsExactly(airport);
        assertThat(tags.tagsOf(TENANT, LOCATION_A))
                .as("an already-tagged branch keeps resolving the tag it carries even once archived")
                .containsExactly(airport);
    }

    @Test
    void settingABranchsTagsReplacesTheWholeSetAndTheAssignmentReadReflectsIt() {
        UUID airport = tags.create(TENANT, "airport", "Аэропорт", ACTOR, "near the airport");
        UUID open247 = tags.create(TENANT, "open-247", "24/7", ACTOR, "never closes");
        UUID parking = tags.create(TENANT, "parking", "Есть парковка", ACTOR, "has a lot");

        tags.setTagsForLocation(TENANT, BRAND, LOCATION_A, List.of(airport, open247), ACTOR, "initial tagging");
        tags.setTagsForLocation(TENANT, BRAND, LOCATION_B, List.of(parking), ACTOR, "initial tagging");

        assertThat(tags.tagsOf(TENANT, LOCATION_A)).containsExactlyInAnyOrder(airport, open247);

        // Replace the whole set: drop open247, keep airport, add parking.
        tags.setTagsForLocation(TENANT, BRAND, LOCATION_A, List.of(airport, parking), ACTOR, "revised tagging");
        assertThat(tags.tagsOf(TENANT, LOCATION_A))
                .as("setTagsForLocation replaces the set rather than only adding")
                .containsExactlyInAnyOrder(airport, parking);

        // The tenant-wide filter-and-group read sees every branch's own tags.
        var assignments = tags.assignments(TENANT);
        assertThat(assignments)
                .filteredOn(a -> a.tagId().equals(parking))
                .extracting(a -> a.locationId())
                .containsExactlyInAnyOrder(LOCATION_A, LOCATION_B);
    }

    @Test
    void clearingABranchsTagsLeavesNoAssignmentBehind() {
        UUID airport = tags.create(TENANT, "airport", "Аэропорт", ACTOR, "near the airport");
        tags.setTagsForLocation(TENANT, BRAND, LOCATION_A, List.of(airport), ACTOR, "tag it");

        tags.setTagsForLocation(TENANT, BRAND, LOCATION_A, List.of(), ACTOR, "untag it");

        assertThat(tags.tagsOf(TENANT, LOCATION_A)).isEmpty();
    }

    private void seedTenancy() {
        for (UUID tenantId : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                            INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                                default_timezone, status, version)
                            VALUES (:id, :slug, 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                            """)
                    .param("id", tenantId)
                    .param("slug", "branch-tags-" + tenantId)
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                        VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                        """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                        INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                            timezone, status, version)
                        VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", LOCATION_A)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        jdbc.sql("""
                        INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                            timezone, status, version)
                        VALUES (:id, :t, :b, 'YUN', 'yunusobod', 'Yunusobod', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", LOCATION_B)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
    }
}
