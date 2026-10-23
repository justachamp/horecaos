package uz.horecaos.platform.fiscal.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The one answer to "does this location have fiscal-capable equipment of its
 * own" (ADR 0038's {@code TERMINAL} responsibility).
 *
 * <p>Payments asks this at exactly one place worth naming twice: whichever
 * module decides a cash tender's fiscal responsibility must never derive its
 * own opinion of what "capable" means, because a second definition is how a
 * checkout comes to believe a terminal exists that the coverage report does
 * not. {@code JdbcFiscalTerminalStore} is the one implementation.
 */
public interface FiscalTerminalDirectory {

    /**
     * @return true when the location has at least one {@code ACTIVE} terminal
     *     whose capability snapshot names {@link
     *     uz.horecaos.platform.fiscal.domain.FiscalTerminal#ISSUE_FISCAL_RECEIPT}
     */
    boolean hasCapableTerminal(UUID tenantId, UUID locationId);

    /**
     * The terminal that issues for this location and this taxpayer: active, capable, and belonging to the legal
     * entity that sold (ADR 0166 Decision 1). A terminal registered under another entity is not an answer, because a
     * receipt issued on another company's equipment names that company.
     */
    default Optional<CapableTerminal> capableTerminal(UUID tenantId, UUID locationId, UUID legalEntityId) {
        return Optional.empty();
    }

    /**
     * @param terminalId the registry id
     * @param reference the registry's reference, which an adapter maps to its device
     * @param providerBindingId the integration binding the terminal issues through, if one is recorded
     */
    record CapableTerminal(
            UUID terminalId,
            String reference,
            UUID legalEntityId,
            @org.jspecify.annotations.Nullable UUID providerBindingId) {}
}
