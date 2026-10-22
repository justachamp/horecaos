package uz.horecaos.platform.courier.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.courier.application.port.CourierAccessMessenger;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierAccessStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierAccessStore.Row;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.iam.api.LocaleVocabulary;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.PasswordRejectedException;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.ProviderUnreachableException;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A courier's first sign-in, and a reset of it (ADR 0178, couriers.md §18).
 *
 * <p>The console never sets, shows or derives a credential. A person with {@code courier.engagement.manage}
 * asks for an {@code INVITE} or a {@code RESET}; the relay texts the courier a one-time link; the courier
 * opens it, chooses a password, and this class has the identity provider set it. {@code
 * CourierAccountProvisioningService} created the account at registration and left it without a password
 * because nothing could give one to a courier: that is the gap this closes.
 *
 * <p>The link's token exists only in the text. This class sees it once, when the courier presents it, and
 * compares its SHA-256 with the one the relay kept; nothing here stores, logs or returns a token.
 *
 * <p><strong>No method here holds a database connection across a call to the identity provider.</strong>
 * Every path is a short statement, then the remote call, then another short statement: the pool is shared by
 * every module, and the accept endpoint needs no token to be reached at all. The accept spends the link
 * <em>first</em>, so a failure afterwards cannot resurrect it; only the two failures that provably changed
 * nothing at the identity provider (a password the realm refuses, a write that never left) put it back.
 */
@Service
public class CourierAccessService {

    private static final Logger log = LoggerFactory.getLogger(CourierAccessService.class);

    /** How long a texted link works. Long, because a courier does not read a text at the instant it arrives. */
    public static final Duration LINK_LIFETIME = Duration.ofHours(72);

    /**
     * How soon the same courier may be sent another. A text costs money and a manager's second tap is
     * usually a double-tap.
     */
    public static final Duration REQUEST_COOLDOWN = Duration.ofSeconds(60);

    /** What the person asking chose. */
    public enum Kind {
        INVITE,
        RESET
    }

    private final JdbcCourierAccessStore store;
    private final JdbcCourierStore couriers;
    private final StaffAccounts accounts;
    private final AuditRecorder audit;
    private final Clock clock;
    private final LocaleVocabulary locales;
    private final ObjectProvider<CourierAccessMessenger> messenger;

    public CourierAccessService(
            JdbcCourierAccessStore store,
            JdbcCourierStore couriers,
            StaffAccounts accounts,
            AuditRecorder audit,
            Clock clock,
            LocaleVocabulary locales,
            ObjectProvider<CourierAccessMessenger> messenger) {
        this.store = store;
        this.couriers = couriers;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
        this.locales = locales;
        this.messenger = messenger;
    }

    /** Whether a text can leave this deployment at all. False until the gateway's courier purpose is connected. */
    public boolean smsConnected() {
        return messenger.getIfAvailable() != null;
    }

    /**
     * Queues an invitation or a reset for a courier, voiding the one they had.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for no such courier; {@code RESOURCE_CONFLICT} when the
     *     courier is archived or has no engagement (access is for someone who works here); {@code
     *     RATE_LIMIT_EXCEEDED} when one was asked for a minute ago
     */
    @Transactional
    public Issued issue(UUID tenantId, UUID courierId, Kind kind, @Nullable String locale, ActorRef actor) {
        CourierRow courier = couriers.findCourier(tenantId, courierId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such courier: " + courierId));
        if (!"ACTIVE".equals(courier.status())
                || couriers.findLiveEngagement(tenantId, courierId).isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Access is given to a courier who works here: this one is archived or has no engagement");
        }
        Instant now = clock.instant();
        Optional<Row> last = store.latest(tenantId, courierId);
        if (last.isPresent()
                && ("QUEUED".equals(last.get().status())
                        || "SENT".equals(last.get().status()))
                && last.get().requestedAt().isAfter(now.minus(REQUEST_COOLDOWN))) {
            long wait = Math.max(
                    1,
                    Duration.between(now, last.get().requestedAt().plus(REQUEST_COOLDOWN))
                            .toSeconds());
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "A link was asked for a moment ago. Wait before asking again.",
                    Map.of("retryAfterSeconds", wait));
        }

        String language = locales.message(locale);
        int voided = store.voidLive(tenantId, courierId, now);
        UUID id = Ids.newId();
        store.queue(id, tenantId, courierId, kind.name(), language, actor.subject(), now);

        audit.record(AuditFact.of(
                        "courier.access." + (kind == Kind.INVITE ? "invited" : "reset_requested"), AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(
                        kind == Kind.INVITE
                                ? "A first sign-in link was asked for (ADR 0178)"
                                : "A reset of the courier's access was asked for (ADR 0178)")
                .usingCapability("courier.engagement.manage")
                // A fresh invitation: no prior state of its own to diff against.
                .changed(ChangeDocuments.created(Map.of(
                        "invitationId",
                        id.toString(),
                        "kind",
                        kind.name(),
                        "locale",
                        language,
                        "voidedEarlier",
                        voided)))
                .correlatedBy(courierId.toString())
                .occurredAt(now)
                .build());
        return new Issued(id, kind.name(), "QUEUED", smsConnected());
    }

    /** Where the courier's access stands, for the detail pane: the newest invitation and whether a text can be sent. */
    public AccessState state(UUID tenantId, UUID courierId) {
        if (couriers.findCourier(tenantId, courierId).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such courier: " + courierId);
        }
        Instant now = clock.instant();
        Optional<Row> latest = store.latest(tenantId, courierId);
        if (latest.isEmpty()) {
            return new AccessState("NONE", null, null, null, null, null, null, smsConnected());
        }
        Row row = latest.get();
        String state = row.status();
        if ("SENT".equals(state) && row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            state = "EXPIRED";
        }
        return new AccessState(
                state,
                row.kind(),
                row.requestedAt(),
                row.sentAt(),
                row.expiresAt(),
                row.acceptedAt(),
                row.lastErrorCode(),
                smsConnected());
    }

    /**
     * What the holder of a live link is shown before choosing a password: which kind it is, the language to
     * show it in, and when it stops working. Marks it opened.
     */
    public Inspection inspect(String token) {
        Instant now = clock.instant();
        Row row = live(token, now);
        store.markOpened(row.id(), Objects.requireNonNull(row.tokenHash()), now);
        return new Inspection(
                row.kind(),
                row.locale(),
                Objects.requireNonNull(row.expiresAt()).toString());
    }

    /**
     * Sets the courier's password, spends the link and ends every session the account holds.
     *
     * @return whether every other session of the account was actually ended
     */
    public boolean accept(String token, String password) {
        Instant now = clock.instant();
        Row row = live(token, now);
        String presented = Objects.requireNonNull(row.tokenHash());
        CourierRow courier = couriers.findCourier(row.tenantId(), row.courierId())
                .filter(found -> "ACTIVE".equals(found.status()))
                .orElseThrow(CourierAccessService::invalid);
        if (accounts.find(courier.principalSubject()).isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This account no longer exists. Ask for a new link.",
                    Map.of("reason", "ACCOUNT_MISSING"));
        }
        if (!store.markAccepted(row.id(), presented, now)) {
            throw invalid();
        }
        try {
            accounts.setPassword(courier.principalSubject(), password);
        } catch (PasswordRejectedException refused) {
            store.restoreSent(row.id(), presented, Objects.requireNonNull(row.expiresAt()), now);
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The password does not meet the policy",
                    Map.of("field", "password", "policy", refused.policy()));
        } catch (ProviderUnreachableException never) {
            store.restoreSent(row.id(), presented, Objects.requireNonNull(row.expiresAt()), now);
            throw never;
        } catch (RuntimeException unset) {
            log.error(
                    "A courier access link was spent and the password write then failed; the link stays spent "
                            + "(invitation {})",
                    row.id(),
                    unset);
            record(
                    row,
                    courier.principalSubject(),
                    "courier.access.password_not_set",
                    Map.of("failure", unset.getClass().getSimpleName()),
                    now);
            throw unset;
        }

        boolean sessionsEnded = endSessions(row, courier.principalSubject(), now);
        record(
                row,
                courier.principalSubject(),
                "courier.access.accepted",
                Map.of("kind", row.kind(), "sessionsEnded", sessionsEnded),
                now);
        return sessionsEnded;
    }

    private boolean endSessions(Row row, String subject, Instant now) {
        try {
            accounts.logoutEverywhere(subject);
            return true;
        } catch (RuntimeException failed) {
            log.error(
                    "A courier's password was set but their sessions were not ended (invitation {})", row.id(), failed);
            record(
                    row,
                    subject,
                    "courier.access.sessions_not_ended",
                    Map.of("failure", failed.getClass().getSimpleName()),
                    now);
            return false;
        }
    }

    /**
     * The courier is the actor of an accept -- possession of the link is what authenticates it. The fact names
     * the invitation and never the number or the token.
     */
    private void record(Row row, String subject, String action, Map<String, Object> facts, Instant now) {
        Map<String, Object> after = new java.util.LinkedHashMap<>(facts);
        after.put("invitationId", row.id().toString());
        audit.record(AuditFact.of(action, AuditClass.SECURITY)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.tenant(row.tenantId()))
                .target("courier", row.courierId())
                .because("The courier used the texted link (ADR 0178)")
                // The link's own status moved from SENT; the facts name how.
                .changed(ChangeDocuments.diff(Map.of("status", "SENT"), after))
                .correlatedBy(row.courierId().toString())
                .occurredAt(now)
                .build());
    }

    /** SHA-256 of a token, hex; what the relay stores and what a presented token is compared by. */
    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Row live(String token, Instant now) {
        Row row = store.byTokenHash(hash(token.strip())).orElseThrow(CourierAccessService::invalid);
        Instant expiresAt = row.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This link has expired. Ask your manager for a new one.",
                    Map.of("reason", "EXPIRED"));
        }
        return row;
    }

    private static ApiException invalid() {
        return new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "This link is not valid. If you already set a password, sign in.",
                Map.of("reason", "INVALID"));
    }

    /** @param smsConnected whether a text can leave this deployment; false means the invitation waits */
    public record Issued(UUID invitationId, String kind, String status, boolean smsConnected) {}

    /**
     * @param state {@code NONE}, {@code QUEUED}, {@code SENT}, {@code EXPIRED} (a sent link past its time),
     *     {@code ACCEPTED}, {@code VOIDED} or {@code FAILED}
     */
    public record AccessState(
            String state,
            @Nullable String kind,
            @Nullable Instant requestedAt,
            @Nullable Instant sentAt,
            @Nullable Instant expiresAt,
            @Nullable Instant acceptedAt,
            @Nullable String lastErrorCode,
            boolean smsConnected) {}

    public record Inspection(String kind, String locale, String expiresAt) {}
}
