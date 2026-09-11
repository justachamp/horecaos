package uz.horecaos.platform.tenancy.application.invitations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.PasswordRejectedException;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationEventStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore.OverviewRow;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant owner's invitation (ADR 0097): queued by onboarding, resent by an
 * operator, opened and accepted by the owner through a one-time link. Every one
 * of those acts is also appended to the invitation's history (ADR 0100), which
 * a resend does not erase the way the invitation row itself is erased.
 *
 * <p>The link's token exists only in the email. This class sees it once, when
 * the owner presents it, and compares its SHA-256 with the one the relay kept;
 * nothing here stores, logs or returns a token.
 *
 * <p>The recipient's address is Keycloak's, read at the moment a screen asks
 * for it and stored nowhere. An operator holding {@link
 * Capability#TENANT_ONBOARDING_MANAGE} -- the capability that supplied the
 * address at onboarding -- sees it in full and leaves an ADR 0029 reveal fact
 * behind; everybody else sees it masked.
 */
@Service
public class OwnerInvitationService implements OwnerInvitations {

    /** How long an emailed link works. */
    public static final Duration LINK_LIFETIME = Duration.ofHours(72);

    public static final Set<String> LOCALES = Set.of("uz", "ru", "en");

    /** Everything that is neither accepted nor unnecessary: the work an operator still has. */
    public static final String OUTSTANDING = "OUTSTANDING";

    /** A tenant whose owner was linked and never invited. Not a stored status. */
    public static final String NONE = "NONE";

    /**
     * At most this many tenants come back from the overview. Each row costs one
     * identity-provider read to resolve its recipient, so the page is bounded
     * by that and not by what a screen would like.
     *
     * <p>The cap is applied by the query and the state filter here, in that
     * order, so the cap is what the screen can miss. The query sorts tenants
     * whose owner is already set up last for exactly that reason: the list is
     * complete for every filter except {@code ACCEPTED} and {@code NOT_NEEDED}
     * until a platform has more than this many tenants still waiting on an
     * owner, at which point onboarding has a bigger problem than a page size.
     */
    public static final int OVERVIEW_LIMIT = 200;

    /** Why an operator is shown a staff address, recorded on every reveal (ADR 0029). */
    static final String RECIPIENT_PURPOSE = "tenancy.onboarding.invitation.recipient";

    /** Most urgent first: what an operator should look at before anything else. */
    private static final List<String> URGENCY =
            List.of("FAILED", "EXPIRED", NONE, "QUEUED", "SENT", "NOT_NEEDED", "ACCEPTED");

    private final JdbcOwnerInvitationStore store;
    private final JdbcOwnerInvitationEventStore events;
    private final StaffAccounts accounts;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final Clock clock;

    public OwnerInvitationService(
            JdbcOwnerInvitationStore store,
            JdbcOwnerInvitationEventStore events,
            StaffAccounts accounts,
            AuthorizationService authorization,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.events = events;
        this.accounts = accounts;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Queues an invitation for an owner whose account has no password yet,
     * unless one was queued before -- a retried onboarding step must not send
     * a second email; a resend is a person's decision.
     *
     * @return true when this call queued it
     */
    @Transactional
    public boolean queueFor(UUID tenantId, String subjectId, String locale, String queuedBy, String correlationId) {
        Instant now = clock.instant();
        String language = LOCALES.contains(locale) ? locale : "ru";
        UUID id = Ids.newId();
        if (!store.queueIfAbsent(id, tenantId, subjectId, language, queuedBy, now)) {
            return false;
        }
        events.append(new JdbcOwnerInvitationEventStore.Entry(
                tenantId,
                id,
                JdbcOwnerInvitationEventStore.QUEUED,
                0,
                language,
                null,
                ActorRef.Type.SYSTEM_JOB.name(),
                queuedBy,
                null,
                now));
        audit.record(AuditFact.of("tenant.owner_invitation.queued", AuditClass.BUSINESS)
                .by(ActorRef.systemJob("tenant-onboarding"))
                .at(ResourceScope.tenant(tenantId))
                .target("tenant.owner_invitation", id)
                .because("Tenant onboarding: the owner's account has no password (ADR 0097)")
                .changed(Map.of("locale", language))
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return true;
    }

    @Override
    @Transactional
    public String inviteIfNeeded(UUID tenantId, String subjectId, String locale, UUID runId) {
        boolean needsPassword =
                accounts.find(subjectId).map(account -> !account.hasPassword()).orElse(true);
        if (!needsPassword) {
            return NOT_NEEDED;
        }
        queueFor(tenantId, subjectId, locale, "onboarding-run:" + runId, runId.toString());
        return QUEUED;
    }

    /**
     * Where the tenant's owner invitation stands, for a named operator: the
     * recipient in full when they hold {@link Capability#TENANT_ONBOARDING_MANAGE}
     * here, and the invitation's history either way.
     *
     * <p>Not read-only: showing an operator a staff address is a reveal, and a
     * reveal writes a fact (ADR 0029).
     */
    @Transactional
    public Optional<OwnerInvitationView> view(UUID tenantId, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        Optional<Row> found = store.latestFor(tenantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Row row = found.get();
        ResourceScope scope = ResourceScope.tenant(tenantId);
        Recipient recipient = recipientOf(row.subjectId(), mayReveal(actor, scope));
        if (recipient.full() != null) {
            recordReveal(scope, actor, 1, now, correlationId);
        }
        return Optional.of(render(row, recipient, now));
    }

    /**
     * Every tenant whose owner has an invitation or is waiting for one (ADR
     * 0100), most urgent first.
     *
     * <p>The caller has already been required to hold {@link
     * Capability#TENANT_ONBOARDING_MANAGE} at platform scope to reach this at
     * all, so the recipients come back in full -- and the one fact recorded
     * here says how many were shown, never which.
     *
     * @param state one stored state, {@link #NONE}, {@link #OUTSTANDING}, or
     *        null for all of them
     */
    @Transactional
    public List<OwnerInvitationOverviewRow> overview(@Nullable String state, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        ResourceScope scope = ResourceScope.platform();
        boolean reveal = mayReveal(actor, scope);
        String wanted = state == null || state.isBlank() ? null : state.strip().toUpperCase(java.util.Locale.ROOT);

        List<OwnerInvitationOverviewRow> rows = new ArrayList<>();
        int revealed = 0;
        for (OverviewRow row : store.overview(OVERVIEW_LIMIT)) {
            String rowState = stateOf(row.status(), row.expiresAt(), now);
            if (!matches(rowState, wanted)) {
                continue;
            }
            Recipient recipient = row.subjectId() == null ? Recipient.UNKNOWN : recipientOf(row.subjectId(), reveal);
            if (recipient.full() != null) {
                revealed++;
            }
            rows.add(new OwnerInvitationOverviewRow(
                    row.tenantId(),
                    row.tenantSlug(),
                    row.tenantName(),
                    row.tenantStatus(),
                    rowState,
                    recipient.full(),
                    recipient.masked(),
                    row.locale(),
                    row.attempts() == null ? 0 : row.attempts(),
                    row.lastErrorCode(),
                    text(row.queuedAt()),
                    text(row.sentAt()),
                    text(row.openedAt()),
                    text(row.acceptedAt()),
                    text(row.expiresAt())));
        }
        rows.sort(Comparator.comparingInt((OwnerInvitationOverviewRow row) -> urgencyOf(row.state()))
                .thenComparing(OwnerInvitationOverviewRow::tenantName));
        if (revealed > 0) {
            recordReveal(scope, actor, revealed, now, correlationId);
        }
        return List.copyOf(rows);
    }

    /**
     * Sends the invitation again, with a new link. The one already sent stops
     * working at once, whether or not the new one is ever delivered.
     */
    @Transactional
    public void resend(UUID tenantId, @Nullable String locale, ActorRef actor, String reason, String correlationId) {
        Optional<Row> existing = store.latestFor(tenantId);
        if (existing.isEmpty()) {
            inviteFirst(tenantId, locale, actor, reason, correlationId);
            return;
        }
        Row row = existing.get();
        if ("ACCEPTED".equals(row.status())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "The owner has already set up their account; they sign in instead");
        }
        String language = locale != null && LOCALES.contains(locale) ? locale : row.locale();
        Instant now = clock.instant();
        String by = actor.subject() == null ? "unknown" : actor.subject();
        if (!store.requeue(row.id(), language, by, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The owner has already set up their account");
        }
        events.append(new JdbcOwnerInvitationEventStore.Entry(
                tenantId,
                row.id(),
                JdbcOwnerInvitationEventStore.RESENT,
                0,
                language,
                row.status(),
                ActorRef.Type.USER.name(),
                by,
                reason,
                now));
        audit.record(AuditFact.of("tenant.owner_invitation.resent", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("tenant.owner_invitation", row.id())
                .because(reason)
                .changed(Map.of("previousStatus", row.status(), "locale", language))
                .usingCapability(Capability.TENANT_ONBOARDING_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * The first invitation for a tenant onboarded before invitations existed:
     * its owner step completed and linked an owner, and nobody was told. The
     * owner is the one that step linked; an owner who already has a password
     * signs in and is not invited.
     */
    private void inviteFirst(
            UUID tenantId, @Nullable String locale, ActorRef actor, String reason, String correlationId) {
        String subjectId = store.ownerFromOnboarding(tenantId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This tenant's onboarding has not linked an owner yet",
                        Map.of("reason", "NO_OWNER")));
        boolean hasPassword =
                accounts.find(subjectId).map(StaffAccount::hasPassword).orElse(false);
        if (hasPassword) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The owner already has a password; they sign in",
                    Map.of("reason", "ALREADY_SET_UP"));
        }
        String language = locale != null && LOCALES.contains(locale) ? locale : "ru";
        Instant now = clock.instant();
        UUID id = Ids.newId();
        String by = actor.subject() == null ? "unknown" : actor.subject();
        store.queueIfAbsent(id, tenantId, subjectId, language, by, now);
        events.append(new JdbcOwnerInvitationEventStore.Entry(
                tenantId,
                id,
                JdbcOwnerInvitationEventStore.QUEUED,
                0,
                language,
                null,
                ActorRef.Type.USER.name(),
                by,
                reason,
                now));
        audit.record(AuditFact.of("tenant.owner_invitation.queued", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("tenant.owner_invitation", id)
                .because(reason)
                .changed(Map.of("locale", language, "firstInvitation", true))
                .usingCapability(Capability.TENANT_ONBOARDING_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * What the owner sees before setting their password: whose invitation it
     * is and which address it went to, masked. Marks it opened.
     */
    @Transactional
    public InvitationInspection inspect(String token) {
        Instant now = clock.instant();
        Row row = live(token, now);
        if (store.markOpened(row.id(), now)) {
            // Only the first open. A mail scanner following the link is not the
            // owner reading their invitation, and opened_at has always meant
            // the first one.
            events.append(new JdbcOwnerInvitationEventStore.Entry(
                    row.tenantId(),
                    row.id(),
                    JdbcOwnerInvitationEventStore.OPENED,
                    row.attempts(),
                    row.locale(),
                    null,
                    "OWNER",
                    row.subjectId(),
                    null,
                    now));
        }
        return new InvitationInspection(
                store.tenantName(row.tenantId()),
                maskFor(row.subjectId()),
                Objects.requireNonNull(row.expiresAt()).toString(),
                row.locale());
    }

    /**
     * Sets the owner's name and password, marks their address verified, and
     * spends the link.
     *
     * @return the name the owner signs in with -- their address, which they
     *         just proved they receive mail at
     */
    @Transactional
    public InvitationAccepted accept(
            String token, String firstName, String lastName, String password, String correlationId) {
        Instant now = clock.instant();
        Row row = live(token, now);
        StaffAccount account = accounts.find(row.subjectId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This invitation's account no longer exists; ask for a new invitation",
                        Map.of("reason", "ACCOUNT_MISSING")));
        try {
            accounts.completeSetup(row.subjectId(), firstName.strip(), lastName.strip(), password);
        } catch (PasswordRejectedException refused) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The password does not meet the policy",
                    Map.of("field", "password", "policy", refused.policy()));
        }
        if (!store.markAccepted(row.id(), now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This invitation changed while it was being accepted");
        }
        events.append(new JdbcOwnerInvitationEventStore.Entry(
                row.tenantId(),
                row.id(),
                JdbcOwnerInvitationEventStore.ACCEPTED,
                row.attempts(),
                row.locale(),
                null,
                "OWNER",
                row.subjectId(),
                null,
                now));
        audit.record(AuditFact.of("tenant.owner_invitation.accepted", AuditClass.SECURITY)
                .by(ActorRef.user(row.subjectId(), null))
                .at(ResourceScope.tenant(row.tenantId()))
                .target("tenant.owner_invitation", row.id())
                .because("The owner set up their account from the invitation (ADR 0097)")
                .changed(Map.of("status", "ACCEPTED", "emailVerified", true))
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return new InvitationAccepted(account.email());
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

    /** The state a screen shows: the stored status, plus the two it does not store. */
    static String stateOf(@Nullable String status, @Nullable Instant expiresAt, Instant now) {
        if (status == null) {
            return NONE;
        }
        return "SENT".equals(status) && expiresAt != null && !expiresAt.isAfter(now) ? "EXPIRED" : status;
    }

    private static boolean matches(String state, @Nullable String wanted) {
        if (wanted == null) {
            return true;
        }
        if (OUTSTANDING.equals(wanted)) {
            return !"ACCEPTED".equals(state) && !"NOT_NEEDED".equals(state);
        }
        return wanted.equals(state);
    }

    private static int urgencyOf(String state) {
        int rank = URGENCY.indexOf(state);
        return rank < 0 ? URGENCY.size() : rank;
    }

    private OwnerInvitationView render(Row row, Recipient recipient, Instant now) {
        List<OwnerInvitationEventView> timeline = events.timeline(row.tenantId(), row.id()).stream()
                .map(event -> new OwnerInvitationEventView(
                        event.type(),
                        event.attempt(),
                        event.locale(),
                        event.outcomeCode(),
                        event.actorType(),
                        event.actorReference(),
                        event.reason(),
                        event.occurredAt().toString()))
                .toList();
        return OwnerInvitationView.of(row, recipient, timeline, now);
    }

    /**
     * Whether this caller may be shown a recipient in full: the capability that
     * chose the address at onboarding is the one that may read it back.
     */
    private boolean mayReveal(ActorRef actor, ResourceScope scope) {
        return actor.type() == ActorRef.Type.USER
                && authorization.has(actor.subject(), Capability.TENANT_ONBOARDING_MANAGE, scope);
    }

    private void recordReveal(ResourceScope scope, ActorRef actor, int count, Instant now, String correlationId) {
        audit.record(AuditFact.of("tenant.owner_invitation.recipient_revealed", AuditClass.SECURITY)
                .by(actor)
                .at(scope)
                .because(RECIPIENT_PURPOSE)
                .changed(Map.of("revealedCount", count))
                .usingCapability(Capability.TENANT_ONBOARDING_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    private Row live(String token, Instant now) {
        Row row = store.byTokenHashForUpdate(hash(token.strip()))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This invitation link is not valid. If you already set your password, sign in.",
                        Map.of("reason", "INVALID")));
        Instant expiresAt = row.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This invitation link has expired. Ask for a new one.",
                    Map.of("reason", "EXPIRED"));
        }
        return row;
    }

    /**
     * The address Keycloak holds, masked and -- when the caller may see it --
     * whole. The identity provider being unreachable costs the address and
     * nothing else: the screen still says where the invitation stands.
     */
    private Recipient recipientOf(String subjectId, boolean reveal) {
        try {
            return accounts.find(subjectId)
                    .map(account -> new Recipient(reveal ? account.email() : null, mask(account.email())))
                    .orElse(Recipient.UNKNOWN);
        } catch (RuntimeException unavailable) {
            return Recipient.UNKNOWN;
        }
    }

    private @Nullable String maskFor(String subjectId) {
        return recipientOf(subjectId, false).masked();
    }

    /** {@code owner@example.uz} as {@code o***r@example.uz}: enough to recognise, not enough to use. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String shown = local.length() <= 2
                ? local.charAt(0) + "***"
                : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return shown + email.substring(at);
    }

    private static @Nullable String text(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** An address in both the forms a screen may be given, and neither is stored. */
    record Recipient(@Nullable String full, @Nullable String masked) {

        static final Recipient UNKNOWN = new Recipient(null, null);

        /** A record's generated {@code toString} would print the address. */
        @Override
        public String toString() {
            return "Recipient[full=" + (full == null ? "none" : "<redacted>") + ", masked=" + masked + "]";
        }
    }

    /**
     * The control plane's view of an invitation; {@code state} adds EXPIRED to
     * the stored status.
     *
     * <p>{@code recipient} is the address in full and is present only for a
     * caller holding {@link Capability#TENANT_ONBOARDING_MANAGE} here (ADR
     * 0100); {@code emailMasked} is what everybody else gets and is what ADR
     * 0097 shipped.
     */
    public record OwnerInvitationView(
            String state,
            @Nullable String recipient,
            @Nullable String emailMasked,
            String locale,
            int attempts,
            @Nullable String lastErrorCode,
            String queuedAt,
            @Nullable String sentAt,
            @Nullable String openedAt,
            @Nullable String acceptedAt,
            @Nullable String expiresAt,
            List<OwnerInvitationEventView> timeline) {

        static OwnerInvitationView of(
                Row row, Recipient recipient, List<OwnerInvitationEventView> timeline, Instant now) {
            return new OwnerInvitationView(
                    stateOf(row.status(), row.expiresAt(), now),
                    recipient.full(),
                    recipient.masked(),
                    row.locale(),
                    row.attempts(),
                    row.lastErrorCode(),
                    row.queuedAt().toString(),
                    text(row.sentAt()),
                    text(row.openedAt()),
                    text(row.acceptedAt()),
                    text(row.expiresAt()),
                    timeline);
        }

        /** A record's generated {@code toString} would print the address. */
        @Override
        public String toString() {
            return "OwnerInvitationView[state=" + state + ", recipient=" + (recipient == null ? "none" : "<redacted>")
                    + ", emailMasked=" + emailMasked + ", attempts=" + attempts + "]";
        }
    }

    /** One thing that happened to an invitation (ADR 0100). */
    public record OwnerInvitationEventView(
            String type,
            int attempt,
            @Nullable String locale,
            @Nullable String outcomeCode,
            String actorType,
            @Nullable String actor,
            @Nullable String reason,
            String occurredAt) {}

    /** One tenant on the cross-tenant overview (ADR 0100). */
    public record OwnerInvitationOverviewRow(
            UUID tenantId,
            String tenantSlug,
            String tenantName,
            String tenantStatus,
            String state,
            @Nullable String recipient,
            @Nullable String emailMasked,
            @Nullable String locale,
            int attempts,
            @Nullable String lastErrorCode,
            @Nullable String queuedAt,
            @Nullable String sentAt,
            @Nullable String openedAt,
            @Nullable String acceptedAt,
            @Nullable String expiresAt) {

        /** A record's generated {@code toString} would print the address. */
        @Override
        public String toString() {
            return "OwnerInvitationOverviewRow[tenantId=" + tenantId + ", state=" + state + ", recipient="
                    + (recipient == null ? "none" : "<redacted>") + ", emailMasked=" + emailMasked + "]";
        }
    }

    /** What an owner holding a live link is shown. */
    public record InvitationInspection(
            String tenantName, @Nullable String emailMasked, String expiresAt, String locale) {}

    /** The name the owner now signs in with. */
    public record InvitationAccepted(String signInName) {

        /** A record's generated {@code toString} would print the address. */
        @Override
        public String toString() {
            return "InvitationAccepted[signInName=<redacted>]";
        }
    }
}
