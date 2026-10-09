package uz.horecaos.platform.iam.application.sessions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffSessions;
import uz.horecaos.platform.iam.api.accounts.StaffSessions.LiveSession;
import uz.horecaos.platform.iam.api.accounts.StaffSessions.SessionsUnavailableException;
import uz.horecaos.platform.iam.api.audit.StaffSecurityAudit;
import uz.horecaos.platform.iam.api.audit.StaffSecurityFact;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffSignInStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffSignInStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * «Мой профиль» → «Сеансы» (ADR 0176): the account's sessions as the person sees them, and the
 * two acts that end them.
 *
 * <p><strong>Keycloak is the authority; the platform's rows label.</strong> A live session is
 * Keycloak's; the row says which device it was. A row the platform thinks open and Keycloak does
 * not list reads as ended. When Keycloak cannot be asked, the list is the platform's rows with a
 * state derived from them, and says so ({@link Availability#UNAVAILABLE}).
 *
 * <p><strong>The acts end sessions at Keycloak first, then record.</strong> Keycloak is called
 * outside any transaction (an admin call must not hold a pooled connection, and cannot be rolled
 * back); when it fails nothing is marked and nothing is audited, and the caller is told the
 * sessions were not ended. When it succeeds, the ledger rows and the {@code SECURITY} fact are
 * written in <em>one</em> transaction, so a failing audit insert leaves the rows open rather than
 * claiming an act with no evidence. Sessions are the person's own: every method acts on the row
 * the token's subject names, and a session id that is not that person's answers as an unknown one.
 */
@Service
public class StaffSessionService {

    private static final Logger log = LoggerFactory.getLogger(StaffSessionService.class);

    /** How many recent rows the card reads: a screen of devices, not an archive. */
    static final int HISTORY_ROWS = 50;

    /** Ended sessions shown beside the live ones. */
    static final int ENDED_SHOWN = 10;

    private final JdbcStaffSignInStore store;
    private final StaffSessions keycloak;
    private final StaffAccounts accounts;
    private final StaffSessionLedger ledger;
    private final StaffSecurityAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry meters;

    public StaffSessionService(
            JdbcStaffSignInStore store,
            StaffSessions keycloak,
            StaffAccounts accounts,
            StaffSessionLedger ledger,
            StaffSecurityAudit audit,
            TransactionTemplate transactions,
            Clock clock,
            MeterRegistry meters) {
        this.store = store;
        this.keycloak = keycloak;
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
        this.meters = meters;
    }

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }

    public enum State {
        ACTIVE,
        ENDED
    }

    /** The closed list a person picks from; never typed. It is the audit fact's {@code because}. */
    public enum Reason {
        LOST_DEVICE,
        SHARED_DEVICE,
        SUSPECTED_COMPROMISE,
        JUST_IN_CASE
    }

    public record Item(
            String id,
            @Nullable DeviceLabel deviceLabel,
            Instant signedInAt,
            Instant lastSeenAt,
            boolean current,
            State state,
            @Nullable EndReason endReason) {}

    public record SessionList(Availability keycloak, List<Item> items) {}

    // ------------------------------------------------------------------------------- the list

    public SessionList list(UUID tenantId, String subjectId, @Nullable String currentSessionId) {
        requireMember(tenantId, subjectId);
        List<Row> rows = store.recent(tenantId, subjectId, HISTORY_ROWS);
        Map<String, Row> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.sessionId(), row));

        List<LiveSession> live;
        try {
            live = keycloak.list(subjectId);
        } catch (SessionsUnavailableException unavailable) {
            count("list", "keycloak_unavailable");
            return new SessionList(Availability.UNAVAILABLE, fromRows(rows, currentSessionId));
        }

        List<Item> active = new ArrayList<>();
        for (LiveSession session : live) {
            Row row = byId.remove(session.id());
            active.add(new Item(
                    session.id(),
                    row == null ? null : row.label(),
                    row == null ? session.startedAt() : row.signedInAt(),
                    session.lastAccessAt(),
                    session.id().equals(currentSessionId),
                    State.ACTIVE,
                    null));
        }
        active.sort(Comparator.comparing(Item::lastSeenAt).reversed());

        // What Keycloak does not list reads as ended, whatever the row believes.
        List<Item> ended = byId.values().stream()
                .sorted(Comparator.comparing(Row::signedInAt).reversed())
                .limit(ENDED_SHOWN)
                .map(row -> new Item(
                        row.sessionId(),
                        row.label(),
                        row.signedInAt(),
                        row.lastSeenAt(),
                        false,
                        State.ENDED,
                        row.endReason()))
                .toList();
        List<Item> items = new ArrayList<>(active);
        items.addAll(ended);
        return new SessionList(Availability.AVAILABLE, items);
    }

    private static List<Item> fromRows(List<Row> rows, @Nullable String currentSessionId) {
        return rows.stream()
                .map(row -> new Item(
                        row.sessionId(),
                        row.label(),
                        row.signedInAt(),
                        row.lastSeenAt(),
                        row.sessionId().equals(currentSessionId) && row.endedAt() == null,
                        row.endedAt() == null ? State.ACTIVE : State.ENDED,
                        row.endReason()))
                .sorted(Comparator.comparing((Item item) -> item.state() == State.ACTIVE ? 0 : 1)
                        .thenComparing(Item::lastSeenAt, Comparator.reverseOrder()))
                .limit(HISTORY_ROWS)
                .toList();
    }

    // -------------------------------------------------------------------- «Выйти на всех устройствах»

    /**
     * Ends every session of the account at Keycloak (both calls), marks this tenant's rows {@code
     * SIGNED_OUT_EVERYWHERE} and writes one {@code SECURITY} fact carrying the count of active
     * sessions and nothing about a device.
     *
     * @throws ApiException {@code IDENTITY_PROVIDER_UNAVAILABLE} when Keycloak could not end them;
     *     nothing is marked or audited then
     */
    public void terminateAll(UUID tenantId, String subjectId, Reason reason, String correlationId) {
        UUID memberId = requireMember(tenantId, subjectId);
        int active = activeSessions(tenantId, subjectId);
        try {
            accounts.logoutEverywhere(subjectId);
        } catch (RuntimeException failed) {
            count("terminate_all", "keycloak_failed");
            log.warn(
                    "Ending an account's sessions failed at the identity provider ({})",
                    failed.getClass().getSimpleName());
            throw notEnded();
        }
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            ledger.endAllInTenant(tenantId, subjectId);
            audit.record(StaffSecurityFact.byStaffMember(
                    "iam.staff_session.terminated_all",
                    subjectId,
                    "iam.staff_account",
                    memberId,
                    reason.name(),
                    Map.of("activeSessions", active),
                    Map.of("activeSessions", 0),
                    correlationId,
                    now));
        });
        count("terminate_all", "ended");
    }

    // ------------------------------------------------------------------------- one session

    /**
     * Ends one session. A session id that is not the caller's answers as an unknown one, and
     * Keycloak is not asked: its delete is not bound to an account, so ownership is decided here,
     * from the caller's own rows and Keycloak's list of the caller's own sessions.
     */
    public void terminateOne(UUID tenantId, String subjectId, String sessionId, Reason reason, String correlationId) {
        UUID memberId = requireMember(tenantId, subjectId);
        if (!ownsSession(tenantId, subjectId, sessionId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such session.");
        }
        int active = activeSessions(tenantId, subjectId);
        try {
            keycloak.end(sessionId);
        } catch (SessionsUnavailableException failed) {
            count("terminate_one", "keycloak_failed");
            throw notEnded();
        }
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            ledger.endOne(subjectId, sessionId);
            audit.record(StaffSecurityFact.byStaffMember(
                    "iam.staff_session.terminated",
                    subjectId,
                    "iam.staff_account",
                    memberId,
                    reason.name(),
                    Map.of("activeSessions", active),
                    Map.of("activeSessions", Math.max(0, active - 1)),
                    correlationId,
                    now));
        });
        count("terminate_one", "ended");
    }

    // ------------------------------------------------------------------------------ internals

    private UUID requireMember(UUID tenantId, String subjectId) {
        return store.memberId(tenantId, subjectId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This company keeps no record for the signed-in account."));
    }

    private boolean ownsSession(UUID tenantId, String subjectId, String sessionId) {
        Optional<Row> recorded = store.bySession(tenantId, subjectId, sessionId);
        if (recorded.isPresent()) {
            return true;
        }
        try {
            return keycloak.list(subjectId).stream()
                    .anyMatch(session -> session.id().equals(sessionId));
        } catch (SessionsUnavailableException unavailable) {
            return false;
        }
    }

    /** Keycloak's count when it can be asked, the platform's open rows otherwise. */
    private int activeSessions(UUID tenantId, String subjectId) {
        try {
            return keycloak.list(subjectId).size();
        } catch (SessionsUnavailableException unavailable) {
            return store.openCount(tenantId, subjectId);
        }
    }

    private static ApiException notEnded() {
        return new ApiException(
                ErrorCode.IDENTITY_PROVIDER_UNAVAILABLE,
                "The sessions were not ended: the sign-in service could not be reached. Nothing changed. "
                        + "Try again in a moment.");
    }

    private void count(String operation, String outcome) {
        Counter.builder("horecaos.iam.staff_session.operations")
                .description("Staff session list and termination outcomes. Labels identify no one.")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
