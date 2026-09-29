package uz.horecaos.platform.dinein.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.dinein.api.TableBindingPort;
import uz.horecaos.platform.dinein.application.QrEntryService.GuestContext;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Answers {@link TableBindingPort} from the guest tokens, the tables and the
 * session (ADR 0047).
 *
 * <p>The write is {@link TableSessionService#addRound} and nothing else: the same
 * unique key on the order, the same branch, fulfilment-mode and currency checks,
 * the same audit fact as the operator's and the guest's own round endpoints. What
 * this adds is only where the table comes from -- the cart's binding, not a
 * request -- and that the attach shares checkout's transaction.
 */
@Component
public class TableBindingPortAdapter implements TableBindingPort {

    /** Recorded as the actor of the round; a guest has no operator subject. */
    private static final String ACTOR_PREFIX = "guest:";

    private final QrEntryService qr;
    private final TableSessionService sessions;
    private final JdbcDineInStore store;

    public TableBindingPortAdapter(QrEntryService qr, TableSessionService sessions, JdbcDineInStore store) {
        this.qr = qr;
        this.sessions = sessions;
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public GuestTable resolveGuestTable(String guestToken) {
        GuestContext guest = qr.resolve(guestToken);
        if (guest.mode() != QrMode.ORDER_AND_PAY) {
            // The same 404 QrEntryController gives a VIEW_ONLY code: a guest whose
            // branch does not take QR orders is not told a cart could have been bound.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This code is not in service. Ask a member of staff.");
        }
        TableRow table = store.findTable(guest.tenantId(), guest.tableId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This code is not in service. Ask a member of staff."));
        return new GuestTable(guest.tenantId(), guest.brandId(), guest.locationId(), table.id(), table.code());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GuestTable> findGuestTable(String guestToken) {
        try {
            return Optional.of(resolveGuestTable(guestToken));
        } catch (ApiException cannotAct) {
            // Every reason a token cannot act (ended, revoked, unknown, a branch that
            // takes no QR orders) is one answer. QrEntryService.resolve and the table
            // read are plain store reads outside any transaction proxy, so catching
            // here does not leave the caller's transaction marked rollback-only.
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSeated(UUID tenantId, UUID tableId) {
        return store.findLiveSessionAtTable(tenantId, tableId).isPresent();
    }

    @Override
    @Transactional
    public void attachRound(UUID tenantId, UUID tableId, UUID orderId, UUID ownerAccountId) {
        SessionRow session = store.findLiveSessionAtTable(tenantId, tableId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Nobody is seated at this table, so there is no bill to put the order on",
                        // Both keys: `conflict` is the dine-in vocabulary, and `reason` is the
                        // one a checkout refusal carries (CheckoutEligibilityGuard), which is
                        // what the storefront reads. The two ways to meet this fact -- the
                        // guard's read-only refusal and this write losing a race -- must not
                        // look different to the guest.
                        Map.of("conflict", "TABLE_NOT_SEATED", "reason", "TABLE_NOT_SEATED")));
        sessions.addRound(
                tenantId,
                session.id(),
                orderId,
                ownerAccountId,
                ACTOR_PREFIX + tableId,
                "Placed from the table via QR checkout");
    }

    @Override
    @Transactional(readOnly = true)
    public void requireLiveSession(UUID tenantId, UUID locationId, UUID sessionId) {
        SessionRow session = sessions.findAtLocation(tenantId, locationId, sessionId);
        if (!session.status().live()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That party has left, so there is no bill to put the order on",
                    // Both keys, for the reason attachRound gives: `conflict` is the dine-in
                    // vocabulary and `reason` is the one a placement refusal carries.
                    Map.of("conflict", "SESSION_NOT_LIVE", "reason", "SESSION_NOT_LIVE"));
        }
    }

    @Override
    @Transactional
    public void attachRoundToSession(
            UUID tenantId, UUID locationId, UUID sessionId, UUID orderId, String actorSubject, String reason) {
        // Checked again here, not only before the order was created: the party may have
        // closed in the seconds checkout took, and the refusal has to be the same one.
        requireLiveSession(tenantId, locationId, sessionId);
        sessions.addRound(tenantId, sessionId, orderId, null, actorSubject, reason);
    }
}
