package uz.horecaos.platform.tenancy.application.invitations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.PasswordRejectedException;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant owner's invitation (ADR 0097): queued by onboarding, resent by an
 * operator, opened and accepted by the owner through a one-time link.
 *
 * <p>The link's token exists only in the email. This class sees it once, when
 * the owner presents it, and compares its SHA-256 with the one the relay kept;
 * nothing here stores, logs or returns a token.
 */
@Service
public class OwnerInvitationService implements OwnerInvitations {

    /** How long an emailed link works. */
    public static final Duration LINK_LIFETIME = Duration.ofHours(72);

    public static final Set<String> LOCALES = Set.of("uz", "ru", "en");

    private final JdbcOwnerInvitationStore store;
    private final StaffAccounts accounts;
    private final AuditRecorder audit;
    private final Clock clock;

    public OwnerInvitationService(
            JdbcOwnerInvitationStore store, StaffAccounts accounts, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.accounts = accounts;
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

    /** Where the tenant's owner invitation stands, for the control plane. */
    @Transactional(readOnly = true)
    public Optional<OwnerInvitationView> view(UUID tenantId) {
        Instant now = clock.instant();
        return store.latestFor(tenantId).map(row -> OwnerInvitationView.of(row, maskedEmail(row.subjectId()), now));
    }

    /**
     * Sends the invitation again, with a new link. The one already sent stops
     * working at once, whether or not the new one is ever delivered.
     */
    @Transactional
    public void resend(UUID tenantId, @Nullable String locale, ActorRef actor, String reason, String correlationId) {
        Row row = store.latestFor(tenantId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This tenant has no owner invitation"));
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
     * What the owner sees before setting their password: whose invitation it
     * is and which address it went to, masked. Marks it opened.
     */
    @Transactional
    public InvitationInspection inspect(String token) {
        Instant now = clock.instant();
        Row row = live(token, now);
        store.markOpened(row.id(), now);
        return new InvitationInspection(
                store.tenantName(row.tenantId()),
                maskedEmail(row.subjectId()),
                java.util.Objects.requireNonNull(row.expiresAt()).toString(),
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

    private @Nullable String maskedEmail(String subjectId) {
        try {
            return accounts.find(subjectId)
                    .map(account -> mask(account.email()))
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            // The screen still says where the invitation stands; only the
            // masked address is missing while the identity provider is down.
            return null;
        }
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

    /** The control plane's view of an invitation; {@code state} adds EXPIRED to the stored status. */
    public record OwnerInvitationView(
            String state,
            @Nullable String emailMasked,
            String locale,
            int attempts,
            @Nullable String lastErrorCode,
            String queuedAt,
            @Nullable String sentAt,
            @Nullable String openedAt,
            @Nullable String acceptedAt,
            @Nullable String expiresAt) {

        static OwnerInvitationView of(Row row, @Nullable String emailMasked, Instant now) {
            boolean expired = "SENT".equals(row.status())
                    && row.expiresAt() != null
                    && !row.expiresAt().isAfter(now);
            return new OwnerInvitationView(
                    expired ? "EXPIRED" : row.status(),
                    emailMasked,
                    row.locale(),
                    row.attempts(),
                    row.lastErrorCode(),
                    row.queuedAt().toString(),
                    text(row.sentAt()),
                    text(row.openedAt()),
                    text(row.acceptedAt()),
                    text(row.expiresAt()));
        }

        private static @Nullable String text(@Nullable Instant instant) {
            return instant == null ? null : instant.toString();
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
