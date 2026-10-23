package uz.horecaos.platform.integration.provider.telegram;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;

/**
 * Removes what the Telegram integration holds that ties an erased customer to a chat (ADR 0029, ADR 0058).
 *
 * <p>Two places. A pending link is a one-time code a customer used to connect their chat or to sign in from it; the rows
 * that name her account (as the customer or as the account the sign-in resolved) are deleted. A SendPulse import report
 * row pairs the chat id of an imported subscriber with the account it was matched or created as, which is precisely "this
 * chat is that customer"; both are set to null on her rows, and the row's outcome and subscription flag stay, which is all
 * the report needs. The staff and operations chat bindings are not hers and are not touched.
 */
@Component
@ErasesCustomerData(
        name = "telegram",
        tables = {"integration.telegram_pending_links", "integration.sendpulse_import_run_rows"})
public class TelegramErasureParticipant implements CustomerErasureParticipant {

    private final JdbcClient jdbc;

    public TelegramErasureParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        int links = jdbc.sql("""
                DELETE FROM integration.telegram_pending_links
                 WHERE tenant_id = :tenantId AND (customer_account_id = :accountId OR auth_account_id = :accountId)
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
        int imported = jdbc.sql("""
                UPDATE integration.sendpulse_import_run_rows
                   SET chat_id = NULL, customer_account_id = NULL
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
        return ErasureCounts.builder()
                .add("integration.telegram_pending_links", links)
                .add("integration.sendpulse_import_run_rows", imported)
                .build();
    }
}
