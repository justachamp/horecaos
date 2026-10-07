package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.BranchDirectory.Branch;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.domain.channel.WeeklySchedule;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontLocationProfileStore;

/**
 * What a customer may be told about a brand's branches (ADR 0069, ADR 0036): the
 * branch's published place and its weekly hours, for active branches of active
 * brands of active tenants only, and never another tenant's.
 */
class BranchDirectoryServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private BranchDirectoryService directory;
    private JdbcServiceabilityStore schedules;
    private final UUID tenant = UUID.randomUUID();
    private final UUID brand = UUID.randomUUID();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        schedules = new JdbcServiceabilityStore(jdbc);
        directory = new BranchDirectoryService(new JdbcStorefrontLocationProfileStore(jdbc), schedules);
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", "dir-" + tenant).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brand).param("tenantId", tenant).update();
    }

    private UUID location(String name, String status, @Nullable String address, @Nullable String phone) {
        UUID id = UUID.randomUUID();
        boolean pinned = address != null;
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version,
                    address_line, district, city, landmark, contact_phone, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'Asia/Tashkent', :status, 0,
                    :address, 'Chilonzor', 'Tashkent', 'Near the metro', :phone,
                    :latitude, :longitude, :source)
                """)
                .param("id", id)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("code", "L" + id.toString().substring(0, 8).toUpperCase())
                .param("slug", "loc-" + id.toString().substring(0, 12))
                .param("name", name)
                .param("status", status)
                .param("address", address)
                .param("phone", phone)
                .param("latitude", pinned ? 41.28 : null, java.sql.Types.DOUBLE)
                .param("longitude", pinned ? 69.20 : null, java.sql.Types.DOUBLE)
                .param("source", pinned ? "MERCHANT_PIN" : "NOT_GEOCODED")
                .update();
        return id;
    }

    private void bindHours(UUID location, FulfillmentMode mode, WeeklySchedule.Rule... rules) {
        UUID schedule = UUID.randomUUID();
        schedules.insertSchedule(schedule, tenant, brand, "Hours " + schedule, false, NOW);
        schedules.replaceRules(schedule, List.of(rules));
        schedules.bindSchedule(tenant, brand, location, mode, schedule, NOW);
    }

    @Test
    @DisplayName("a branch's published place and weekly hours are returned, by name, for active branches only")
    void activeBranchesWithTheirPlaceAndHours() {
        UUID zebra = location("Zebra", "ACTIVE", "Bunyodkor 12", "+998712000000");
        UUID alpha = location("Alpha", "ACTIVE", "Navoi 5", null);
        location("Dormant", "SUSPENDED", "Nowhere 1", null);
        bindHours(
                zebra,
                FulfillmentMode.PICKUP,
                new WeeklySchedule.Rule(1, LocalTime.of(9, 0), LocalTime.of(23, 0)),
                new WeeklySchedule.Rule(2, LocalTime.of(9, 0), LocalTime.of(23, 0)));
        bindHours(
                zebra, FulfillmentMode.DELIVERY, new WeeklySchedule.Rule(1, LocalTime.of(10, 0), LocalTime.of(22, 0)));

        List<Branch> branches = directory.activeBranches(tenant, brand);

        assertThat(branches).extracting(Branch::name).containsExactly("Alpha", "Zebra");
        Branch zebraBranch = branches.get(1);
        assertThat(zebraBranch.locationId()).isEqualTo(zebra);
        assertThat(zebraBranch.addressLine()).isEqualTo("Bunyodkor 12");
        assertThat(zebraBranch.district()).isEqualTo("Chilonzor");
        assertThat(zebraBranch.landmark()).isEqualTo("Near the metro");
        assertThat(zebraBranch.contactPhone()).isEqualTo("+998712000000");
        assertThat(zebraBranch.point()).isNotNull();
        assertThat(zebraBranch.timezone()).isEqualTo("Asia/Tashkent");
        assertThat(zebraBranch.weeklyHours().get(FulfillmentMode.PICKUP)).hasSize(2);
        assertThat(zebraBranch.weeklyHours().get(FulfillmentMode.DELIVERY))
                .singleElement()
                .satisfies(window -> assertThat(window.opensAt()).isEqualTo(LocalTime.of(10, 0)));

        Branch alphaBranch = branches.getFirst();
        assertThat(alphaBranch.locationId()).isEqualTo(alpha);
        assertThat(alphaBranch.weeklyHours())
                .as("a branch with no timetable has unknown hours, never 'always open'")
                .isEmpty();
    }

    @Test
    @DisplayName("a branch with no coordinates is still listed, with no point")
    void aBranchWithoutCoordinates() {
        location("Unpinned", "ACTIVE", null, null);

        assertThat(directory.activeBranches(tenant, brand)).singleElement().satisfies(branch -> {
            assertThat(branch.point()).isNull();
            assertThat(branch.addressLine()).isNull();
        });
    }

    @Test
    @DisplayName("another tenant's brand, or a brand that is not active, yields nothing")
    void isolation() {
        location("Alpha", "ACTIVE", "Navoi 5", null);

        assertThat(directory.activeBranches(UUID.randomUUID(), brand)).isEmpty();
        assertThat(directory.activeBranches(tenant, UUID.randomUUID())).isEmpty();

        jdbc.sql("UPDATE tenant.brands SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", brand)
                .update();
        assertThat(directory.activeBranches(tenant, brand)).isEmpty();
    }
}
