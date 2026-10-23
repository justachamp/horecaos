package uz.horecaos.platform.dinein.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;

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
 *       or closed: the {@code opened_by_account_id} column, and the {@code
 *       'guest:<accountId>'} the guest route also writes into {@code opened_by}, which
 *       is neutralised because the column is NOT NULL. The session, its bill and its
 *       audit trail stay: financial history survives and the person does not (ADR
 *       0029).
 * </ol>
 *
 * <p>The reservations a guest made are hers too: a booking made with her account, or typed by a host for a guest who
 * quoted a number her account held, keeps a name, two numbers and a note under the envelope (V0034). Those are
 * overwritten and the booking's own facts (party, window, status, table) stay.
 *
 * <p>Idempotent under retry, as {@link CustomerErasureParticipant} requires: a second
 * call finds no live claim and nothing left to clear.
 */
@Component
@ErasesCustomerData(
        name = "dinein",
        tables = {"dinein.table_sessions", "dinein.reservations"})
public class DineInErasureParticipant implements CustomerErasureParticipant {

    private static final ActorRef ERASURE = ActorRef.systemJob("dinein.customer-erasure");

    private static final String TOMBSTONE = "ERASED";
    private static final String RESERVATION_TABLE = "dinein.reservations";

    private final JdbcDineInStore store;
    private final TableSessionService sessions;
    private final FieldProtection protection;

    public DineInErasureParticipant(JdbcDineInStore store, TableSessionService sessions, FieldProtection protection) {
        this.store = store;
        this.sessions = sessions;
        this.protection = protection;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        int lapsed = 0;
        for (SessionRow claim : store.liveUnconfirmedClaimsOf(tenantId, customerAccountId)) {
            sessions.lapseClaim(tenantId, claim.id(), ERASURE);
            lapsed++;
        }
        int cleared = store.clearClaimant(tenantId, customerAccountId);
        int reservations = store.eraseReservationsOf(
                tenantId,
                customerAccountId,
                held.phoneLookupHashes(),
                (reservationId, column) -> protection
                        .protect(
                                tenantId,
                                DataClass.PERSONAL,
                                new RecordRef(RESERVATION_TABLE, column, reservationId),
                                TOMBSTONE)
                        .serialize());
        return ErasureCounts.builder()
                .add("dinein.table_sessions", lapsed + cleared)
                .add(RESERVATION_TABLE, reservations)
                .build();
    }
}
