package uz.horecaos.platform.integration.provider.telegram;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The installation-level fallback for "which brand's flow answers this chat"
 * (ADR 0059 stage 1, V0108): read only when {@link TelegramBindingStore#scopeForChat}
 * has nothing for a chat that has never bound anything yet — a chat that has
 * bound something (a group link, a customer's own {@code /start <code>}) keeps
 * resolving its brand from that binding, since a binding can move an
 * installation's chats across brands in a way this column, one fixed value
 * per installation, never could.
 */
@Repository
public class TelegramInstallationBrandLookup {

    private final JdbcClient jdbc;

    public TelegramInstallationBrandLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> brandFor(UUID installationId) {
        return jdbc.sql("SELECT brand_id FROM integration.installations WHERE id = :id")
                .param("id", installationId)
                .query(UUID.class)
                .optional();
    }
}
