package uz.horecaos.platform.integration.provider.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link TelegramStaffLinkService#listForTenant}, added for staff-and-access.md's
 * People screen and person-record Безопасность tab (operations IA §9.1): "a
 * staff row's Telegram state is real data worth showing".
 */
class TelegramStaffLinkServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1213a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1213a2");
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-09-02T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TelegramStaffLinkService links;

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
        jdbc.sql("TRUNCATE TABLE integration.telegram_staff_links CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.telegram_staff_link_codes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
        links = new TelegramStaffLinkService(jdbc, clock, Duration.ofMinutes(15));

        insertTenant(TENANT, "staff-link-test");
        insertTenant(OTHER_TENANT, "staff-link-test-other");
    }

    @Test
    void listsOnlyThisTenantsLinks() throws Exception {
        String code = links.issueCode(TENANT, "staff-1");
        var pending = links.resolve(code).orElseThrow();
        links.link(TENANT, pending.id(), "staff-1", 555_000_001L);

        String otherCode = links.issueCode(OTHER_TENANT, "staff-2");
        var otherPending = links.resolve(otherCode).orElseThrow();
        links.link(OTHER_TENANT, otherPending.id(), "staff-2", 555_000_002L);

        assertThat(links.listForTenant(TENANT)).singleElement().satisfies(view -> {
            assertThat(view.principalSubject()).isEqualTo("staff-1");
            assertThat(view.telegramUserId()).isEqualTo(555_000_001L);
            assertThat(view.linkedAt()).isEqualTo(CLOCK_INSTANT);
        });
    }

    @Test
    void anUnlinkedTenantReportsNoLinks() {
        assertThat(links.listForTenant(TENANT)).isEmpty();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }
}
