package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 10.5's public reads, unauthenticated — hostname resolution, SEO
 * presentation, and static pages.
 *
 * <p>Hits the real filter chain with no token, the same way {@code
 * StorefrontTermsControllerEndpointTests} proves {@code
 * SecurityConfiguration}'s own allowlist rather than just the controller.
 * Fixtures write the tables directly rather than going through the console
 * controllers — this class is proving the public read, not the authoring
 * path {@code ChannelSetupControllerEndpointTests}/{@code
 * ChannelPagesControllerEndpointTests} already cover.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontChannelSetupControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a22-3000-7000-8000-0000000000a1");
    private static final UUID CHANNEL = UUID.fromString("018f9a22-3000-7000-8000-0000000000b1");
    private static final UUID ARCHIVED_CHANNEL = UUID.fromString("018f9a22-3000-7000-8000-0000000000b2");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront channel setup endpoint test");
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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'storefront-channel-setup', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, 'WEB1', 'WEB', 'Website', 'ACTIVE', 1)
                """).param("id", CHANNEL).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, 'WEB2', 'WEB', 'Retired site', 'ARCHIVED', 1)
                """).param("id", ARCHIVED_CHANNEL).param("tenantId", TENANT).update();
    }

    // ------------------------------------------------------------ hostname

    @Test
    void anUnclaimedHostnameIsNotFound() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/storefront/channel-hostnames/nobody-claims-this.example.uz"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aVerifiedHostnameResolvesToItsTenantAndChannel() throws Exception {
        insertHostname("verified.example.uz", true);

        MvcResult result = mvc.perform(get("/api/v1/storefront/channel-hostnames/verified.example.uz"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains(TENANT.toString())
                .contains(CHANNEL.toString())
                .contains("\"verified\":true");
    }

    @Test
    void anUnverifiedCustomHostnameDoesNotResolve() throws Exception {
        insertHostname("unverified.example.uz", false);

        MvcResult result = mvc.perform(get("/api/v1/storefront/channel-hostnames/unverified.example.uz"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an unconfirmed custom domain must not route traffic — ChannelSetupService#resolveHostname")
                .isEqualTo(404);
    }

    // --------------------------------------------------------- presentation

    @Test
    void presentationReadsBackWhatWasSet() throws Exception {
        UUID ogImage = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.channel_presentation (tenant_id, channel_id, seo_title, seo_description, og_image_asset_id)
                VALUES (:tenantId, :channelId, 'Tandir House', 'Fresh bread, delivered.', :ogImage)
                """)
                .param("tenantId", TENANT)
                .param("channelId", CHANNEL)
                .param("ogImage", ogImage)
                .update();

        MvcResult result = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/channels/WEB1/presentation"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("Tandir House")
                .contains(ogImage.toString());
    }

    @Test
    void anArchivedChannelIsNotServed() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/channels/WEB2/presentation"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // -------------------------------------------------------------- pages

    @Test
    void aPublishedPageRendersInItsPublishedLocale() throws Exception {
        insertPage("about", 1, "en", "About Tandir House.");

        MvcResult result = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/channels/WEB1/pages/about")
                        .queryParam("locale", "en"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("About Tandir House.");
    }

    @Test
    void aPageWithNoTextInTheRequestedLocaleIsNotFound() throws Exception {
        insertPage("about", 1, "en", "About Tandir House.");

        MvcResult result = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/channels/WEB1/pages/about")
                        .queryParam("locale", "ru"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void anUnpublishedPageIsNotFound() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/channels/WEB1/pages/contacts")
                        .queryParam("locale", "en"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theHighestVersionIsAlwaysWhatRenders() throws Exception {
        insertPage("about", 1, "en", "Version one.");
        insertPage("about", 2, "en", "Version two.");

        MvcResult result = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/channels/WEB1/pages/about")
                        .queryParam("locale", "en"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Version two.")
                .doesNotContain("Version one.");
    }

    private void insertHostname(String hostname, boolean verified) {
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified)
                VALUES (:tenantId, :channelId, :hostname, :verified)
                """)
                .param("tenantId", TENANT)
                .param("channelId", CHANNEL)
                .param("hostname", hostname)
                .param("verified", verified)
                .update();
    }

    private void insertPage(String slug, int version, String locale, String body) {
        UUID pageId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.channel_pages (id, tenant_id, channel_id, slug, version, published_by, published_at)
                VALUES (:id, :tenantId, :channelId, :slug, :version, 'fixture', now())
                """)
                .param("id", pageId)
                .param("tenantId", TENANT)
                .param("channelId", CHANNEL)
                .param("slug", slug)
                .param("version", version)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.channel_page_contents (id, tenant_id, channel_page_id, locale, body)
                VALUES (:id, :tenantId, :pageId, :locale, :body)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("pageId", pageId)
                .param("locale", locale)
                .param("body", body)
                .update();
    }
}
