package uz.horecaos.platform.fiscal.api;

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
}
