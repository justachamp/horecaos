package uz.horecaos.platform.dinein.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;

/**
 * Clears a customer's account id from the table sessions they opened (ADR 0015,
 * ADR 0143: "the session row now carries a customer account id ... erasure must
 * clear it").
 *
 * <p>An account id is an identifier, not the name, phone or address ADR 0029
 * protects, but it is one more place a person's account lives, and erasure
 * anonymises the person everywhere the platform keeps them. Two things happen, in
 * the erasure's own transaction:
 *
 * <ol>
 *   <li>A claim the account still holds, unconfirmed and live, is lapsed. An erased
 *       customer's provisional claim must not keep a table from the room, and it is the
 *       one row whose claimant id the schema refuses to clear
 *       ({@code ck_session_claim_shape}: a live unconfirmed claim always names its
 *       claimant, because {@code ux_claim_account_branch} is what bounds an account to
 *       one).
 *   <li>The id is cleared from every guest-opened session of theirs that is confirmed
 *       or closed. The session, its bill and its audit trail stay: financial history
 *       survives and the person does not (ADR 0029).
 * </ol>
 *
 * <p>Idempotent under retry, as {@link CustomerErasureParticipant} requires: a second
 * call finds no live claim and nothing left to clear.
 */
@Component
public class DineInErasureParticipant implements CustomerErasureParticipant {

    private static final ActorRef ERASURE = ActorRef.systemJob("dinein.customer-erasure");

    private final JdbcDineInStore store;
    private final TableSessionService sessions;

    public DineInErasureParticipant(JdbcDineInStore store, TableSessionService sessions) {
        this.store = store;
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public void erase(UUID tenantId, UUID customerAccountId) {
        for (SessionRow claim : store.liveUnconfirmedClaimsOf(tenantId, customerAccountId)) {
            sessions.lapseClaim(tenantId, claim.id(), ERASURE);
        }
        store.clearClaimant(tenantId, customerAccountId);
    }
}
