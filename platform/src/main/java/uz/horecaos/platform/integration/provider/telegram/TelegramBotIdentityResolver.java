package uz.horecaos.platform.integration.provider.telegram;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;

/**
 * The tenant's active Telegram installation, and the bot's own {@code
 * @username} it answers to — needed for a customer deep link (ADR 0058
 * stage 2: {@code https://t.me/<bot>?start=<code>}), which names the bot by
 * username in a URL and has no other way to.
 *
 * <p>Nothing on {@code integration.installations} caches a bot's username
 * today — {@link TelegramWebhookInstallationLookup}'s own javadoc-adjacent
 * code calls {@code getMe} live, every time, only inside {@link
 * TelegramRightsVerifier}, which never needs to remember the answer between
 * calls. A deep-link URL is built far more often than that, on a storefront
 * request path a customer is waiting on, so this class resolves once and
 * caches the result on the installation's own {@code non_sensitive_config} —
 * the same "reference/config lives with the installation" shape {@link
 * JdbcSmsAccountLookup} already uses for a provider's login and sender.
 */
@Repository
public class TelegramBotIdentityResolver {

    static final String BOT_USERNAME_KEY = "botUsername";

    private final JdbcClient jdbc;
    private final TelegramBotApiClient bots;
    private final SecretResolver secrets;

    public TelegramBotIdentityResolver(JdbcClient jdbc, TelegramBotApiClient bots, SecretResolver secrets) {
        this.jdbc = jdbc;
        this.bots = bots;
        this.secrets = secrets;
    }

    /** The tenant's active Telegram NOTIFICATION installation, if one has been provisioned. */
    public Optional<Installation> activeInstallation(UUID tenantId) {
        return jdbc.sql("""
                SELECT i.id, i.secret_reference, e.base_url, i.non_sensitive_config ->> 'botUsername' AS bot_username
                FROM integration.installations i
                JOIN integration.provider_environments e ON e.code = i.environment_code
                WHERE i.tenant_id = :tenantId AND i.provider_category = 'NOTIFICATION'
                  AND i.provider_type = 'TELEGRAM' AND i.status = 'ACTIVE'
                ORDER BY i.created_at
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new Installation(
                        row.getObject("id", UUID.class),
                        row.getString("secret_reference"),
                        row.getString("base_url"),
                        row.getString("bot_username")))
                .optional();
    }

    /**
     * The bot's own {@code @username}: the cached value if one is already on
     * file, otherwise resolved live via {@code getMe} and cached for next
     * time.
     *
     * @return empty when {@code getMe} fails — no installation, however
     *         freshly provisioned, should turn a temporary Bot API problem
     *         into a broken deep link the caller cannot explain
     */
    public Optional<String> resolveUsername(UUID tenantId, Installation installation) {
        if (installation.botUsername() != null) {
            return Optional.of(installation.botUsername());
        }

        SecretReference reference = SecretReference.parse(installation.secretReference());
        ProviderCall call = new ProviderCall(
                installation.baseUrl(), secrets.resolve(reference).reveal(), null, Duration.ofSeconds(15));
        TelegramCallResult result = bots.getMe(call);
        if (!(result instanceof TelegramCallResult.Success success)
                || !(success.result().get("username") instanceof String username)
                || username.isBlank()) {
            return Optional.empty();
        }

        jdbc.sql("""
                UPDATE integration.installations
                SET non_sensitive_config = jsonb_set(non_sensitive_config, '{botUsername}', to_jsonb(:username::text), true),
                    updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", installation.id())
                .param("username", username)
                .update();

        return Optional.of(username);
    }

    public record Installation(
            UUID id,
            String secretReference,
            String baseUrl,
            @Nullable String botUsername) {}
}
