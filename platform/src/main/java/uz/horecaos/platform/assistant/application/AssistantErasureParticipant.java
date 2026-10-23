package uz.horecaos.platform.assistant.application;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * Unlinks an erased customer from the assistant's turn ledger (ADR 0029, ADR 0069).
 *
 * <p>The assistant stores no words of its own: the customer's message and its reply live, encrypted, in {@code
 * conversations.conversation_messages}, which the conversations participant deletes. What the ledger does keep is the one
 * identifier of the customer the model is ever given, ADR 0029's per-tenant keyed hash of the account (the
 * {@code customer_pseudonym}). A hash anyone holding the tenant key can recompute from the account id is still a way to
 * say these turns were hers, so it is set to null on her turns; the turn, its cost, its outcome and what it cited stay,
 * because the tenant's spend ledger has to reconcile.
 *
 * <p>The assistant reaches no other module's schema (ADR 0069), so it finds her turns the way it wrote them: by
 * recomputing the pseudonym.
 */
@Component
@ErasesCustomerData(
        name = "assistant",
        tables = {"assistant.turns"})
public class AssistantErasureParticipant implements CustomerErasureParticipant {

    private final JdbcClient jdbc;
    private final FieldProtection protection;

    public AssistantErasureParticipant(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        String pseudonym = protection.lookupHash(
                tenantId, AssistantTurnService.CUSTOMER_PSEUDONYM_DOMAIN, customerAccountId.toString());
        int turns = jdbc.sql("""
                UPDATE assistant.turns
                   SET customer_pseudonym = NULL
                 WHERE tenant_id = :tenantId AND customer_pseudonym = :pseudonym
                """)
                .param("tenantId", tenantId)
                .param("pseudonym", pseudonym)
                .update();
        return ErasureCounts.of("assistant.turns", turns);
    }
}
