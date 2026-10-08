package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Who took orders at a branch in a window (ADR 0156): the subjects that created or accepted an
 * order there.
 *
 * <p>Backs one thing and nothing else: «Работает без смены» on the manager's staff-shifts screen,
 * a derived notice whose only effect is that the manager can open or correct a shift afterwards.
 * Attribution exists for orders (an operator who typed or accepted one is a named subject) and
 * does not exist for kitchen work, where a shared device principal advances every ticket (ADR
 * 0079), so this can raise the condition for people who take orders and for nobody who only cooks.
 * The screen says so in words.
 *
 * <p>Read through this port rather than by {@code workforce} reading {@code ordering.orders}
 * (ADR 0023). The subjects are Keycloak subjects, the string {@code iam.grants.principal_subject}
 * already carries; a name never crosses it.
 */
public interface OperatorActivity {

    /**
     * The Keycloak subjects that created or accepted at least one order at this branch with an
     * activity instant in {@code [fromInclusive, toExclusive)}. Tenant- and branch-scoped: an
     * order of another tenant is never counted.
     */
    Set<String> operatorsAt(UUID tenantId, UUID locationId, Instant fromInclusive, Instant toExclusive);
}
