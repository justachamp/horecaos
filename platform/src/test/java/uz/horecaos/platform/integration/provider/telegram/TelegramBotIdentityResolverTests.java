package uz.horecaos.platform.integration.provider.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The bot username resolution a customer deep link needs (ADR 0058 stage 2)
 * — untouched by every other Telegram suite in this genre, since a group or
 * staff {@code /link} handshake never needs the bot's own {@code @username},
 * only its numeric id. Against a real PostgreSQL and {@link FakeTelegramBotApi},
 * the same "whole path, not a mock" discipline every sibling in this genre
 * follows.
 */
class TelegramBotIdentityResolverTests {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private FakeTelegramBotApi bot;
    private TelegramBotIdentityResolver resolver;

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
    void setUp() throws Exception {
        bot = FakeTelegramBotApi.start();
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        Clock clock = Clock.fixed(Instant.parse("2026-08-31T09:00:00Z"), ZoneOffset.UTC);
        SecretResolver secrets = new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);
        resolver = new TelegramBotIdentityResolver(
                jdbc, new TelegramBotApiClient(JsonMapper.builder().build()), secrets);
    }

    @Test
    @DisplayName("no Telegram installation answers empty rather than throwing")
    void noInstallationAnswersEmpty() {
        assertThat(resolver.activeInstallation(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("the username is resolved via getMe once and cached on the installation row thereafter")
    void resolvesAndCachesTheUsernameOnFirstUse() {
        UUID tenant = seedTenant();
        bot.setBotUsername("horecaos_test_bot");
        seedInstallation(tenant);

        TelegramBotIdentityResolver.Installation firstRead =
                resolver.activeInstallation(tenant).orElseThrow();
        assertThat(firstRead.botUsername()).isNull();

        Optional<String> resolved = resolver.resolveUsername(tenant, firstRead);
        assertThat(resolved).contains("horecaos_test_bot");
        assertThat(bot.getMeCallCount()).isEqualTo(1);

        // The cache is the installation row itself: a fresh read now carries
        // the username without another getMe call, and resolving from that
        // fresh read does not call getMe a second time either.
        TelegramBotIdentityResolver.Installation secondRead =
                resolver.activeInstallation(tenant).orElseThrow();
        assertThat(secondRead.botUsername()).isEqualTo("horecaos_test_bot");

        assertThat(resolver.resolveUsername(tenant, secondRead)).contains("horecaos_test_bot");
        assertThat(bot.getMeCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a getMe answer with no username answers empty rather than caching a null")
    void aGetMeAnswerWithNoUsernameAnswersEmpty() {
        UUID tenant = seedTenant();
        // Deliberately not calling bot.setBotUsername: the real Bot API
        // answers this way for a bot with no @username set at all.
        seedInstallation(tenant);

        TelegramBotIdentityResolver.Installation installation =
                resolver.activeInstallation(tenant).orElseThrow();

        assertThat(resolver.resolveUsername(tenant, installation)).isEmpty();
    }

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("slug", "bot-identity-" + id.toString().substring(0, 8))
                .update();
        return id;
    }

    private void seedInstallation(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('bot-identity-env', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT (code) DO UPDATE SET base_url = EXCLUDED.base_url
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'bot-identity-env',
                        'Test bot', 'ACTIVE', :secret, :secret)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("secret", "horecaos:local:provider_notification:platform:telegram-bot")
                .update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }
}
