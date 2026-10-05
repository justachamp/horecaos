package uz.horecaos.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelInstallationChanged;
import uz.horecaos.platform.tenancy.application.SalesChannelService;
import uz.horecaos.platform.tenancy.application.SalesChannelService.CreateChannelCommand;
import uz.horecaos.platform.tenancy.application.SalesChannelService.UpdateChannelCommand;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0141 Decision 7's marker for a channel's installation, on the producing side: every write
 * that can change which installation a sales channel resolves for, or whether that channel is
 * active, tells the marketplace reconciler (which listens in {@code integration}, so the event
 * lives in {@code tenancy.api}).
 *
 * <p>Asserted against the real service and a real database, with a recording publisher as the
 * only fake: what matters is that the write and the announcement agree about the installation,
 * the one it left included.
 */
class SalesChannelInstallationAnnouncementTests {

    private static final UUID TENANT = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private SalesChannelService channels;
    private final List<Object> published = new ArrayList<>();
    private UUID installationA;
    private UUID installationB;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
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
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'install-announce', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('announce-env', 'MARKETPLACE', 'FAKE_EDA', 'https://sandbox.example.test', false, 'sandbox.example.test')
                ON CONFLICT (code) DO NOTHING
                """).update();
        installationA = installation("A");
        installationB = installation("B");
        published.clear();
        channels = new SalesChannelService(
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-10-03T09:00:00Z"), ZoneOffset.UTC),
                published::add);
    }

    @Test
    @DisplayName("a channel created on an installation announces that installation")
    void createAnnouncesTheInstallation() {
        SalesChannel channel = channels.create(
                TENANT, new CreateChannelCommand("EDA", "AGGREGATOR", "Eda", null, false, true, installationA));

        assertThat(announcements()).singleElement().satisfies(event -> {
            assertThat(event.channelId()).isEqualTo(channel.id());
            assertThat(event.installationIds()).containsExactly(installationA);
        });
    }

    @Test
    @DisplayName("a channel with no installation announces nothing: no binding resolves through it")
    void aChannelWithNoInstallationIsSilent() {
        channels.create(TENANT, new CreateChannelCommand("WEB", "WEB", "Web", null, false, true, null));

        assertThat(announcements()).isEmpty();
    }

    @Test
    @DisplayName("repointing a channel announces both the installation it left and the one it joined")
    void repointingAnnouncesBoth() {
        SalesChannel channel = channels.create(
                TENANT, new CreateChannelCommand("EDA", "AGGREGATOR", "Eda", null, false, true, installationA));
        published.clear();

        channels.update(
                TENANT,
                channel.id(),
                new UpdateChannelCommand("Eda", null, false, true, installationB, null, null, null),
                channel.version());

        assertThat(announcements())
                .singleElement()
                .satisfies(event ->
                        assertThat(event.installationIds()).containsExactlyInAnyOrder(installationA, installationB));
    }

    @Test
    @DisplayName("an edit that leaves the installation alone announces nothing")
    void anUnrelatedEditIsSilent() {
        SalesChannel channel = channels.create(
                TENANT, new CreateChannelCommand("EDA", "AGGREGATOR", "Eda", null, false, true, installationA));
        published.clear();

        channels.update(
                TENANT,
                channel.id(),
                new UpdateChannelCommand("Eda renamed", null, false, true, installationA, null, null, null),
                channel.version());

        assertThat(announcements()).isEmpty();
    }

    @Test
    @DisplayName(
            "pausing, reopening and retiring a channel each announce its installation: the binding's channel changed")
    void statusTransitionsAnnounce() {
        SalesChannel channel = channels.create(
                TENANT, new CreateChannelCommand("EDA", "AGGREGATOR", "Eda", null, false, true, installationA));
        published.clear();

        SalesChannel paused = channels.deactivate(TENANT, channel.id(), channel.version());
        SalesChannel reopened = channels.reactivate(TENANT, channel.id(), paused.version());
        channels.archive(TENANT, channel.id(), reopened.version());

        assertThat(announcements())
                .hasSize(3)
                .allSatisfy(event -> assertThat(event.installationIds()).containsExactly(installationA));
    }

    private List<SalesChannelInstallationChanged> announcements() {
        return published.stream()
                .filter(SalesChannelInstallationChanged.class::isInstance)
                .map(SalesChannelInstallationChanged.class::cast)
                .toList();
    }

    private UUID installation(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'MARKETPLACE', 'FAKE_EDA', 'announce-env', :name, 'ACTIVE')
                """).param("id", id).param("t", TENANT).param("name", name).update();
        return id;
    }
}
