package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The server side of the AUTH share-contact sign-in handshake (ADR 0063): a
 * storefront-minted {@code /start auth_<code>} deep link, redeemed by a
 * Telegram {@code request_contact} share, polled back into a session by the
 * storefront that minted the code.
 *
 * <p>Rides {@code integration.telegram_pending_links} exactly as
 * {@link TelegramLinkService} and {@link TelegramCustomerLinkService} do —
 * {@code audience = 'AUTH'} rather than a parallel table — but unlike either of
 * those, an AUTH row is minted knowing no account at all: no session exists yet
 * (unlike CUSTOMER, whose code is minted from one) and no principal requested it
 * (unlike OPERATIONS/PLATFORM). What it discovers, in order, across three
 * separate calls a webhook update and a storefront poll each make on their own
 * side of Telegram's asynchrony, is: which chat is answering it
 * ({@link #beginAwaitingContact}/{@link #resolveAwaitingContact} — a contact
 * message carries no payload of its own to correlate with, unlike a callback
 * button's {@code callback_data}), which account it redeemed to
 * ({@link #redeem}), and whether the storefront has claimed that redemption for
 * a session yet ({@link #claimSession}).
 *
 * <p><strong>Nothing recoverable is ever stored here.</strong> {@code auth_account_id}
 * is an account id, not a secret; the session bearer itself is minted fresh, once,
 * by {@link #claimSession}'s caller — see that method's own doc and
 * {@code customers.api.CustomerTelegramSignIn}'s.
 */
@Service
public class TelegramAuthLinkService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration codeTtl;

    public TelegramAuthLinkService(
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.notifications.telegram.auth-code-ttl:PT15M}") Duration codeTtl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.codeTtl = codeTtl;
    }

    /**
     * Mints the code behind {@code https://t.me/<bot>?start=auth_<code>}, from an
     * unauthenticated storefront request — there is no session to issue it from,
     * which is the whole point of this handshake.
     */
    @Transactional
    public String issueCode(UUID tenantId, UUID brandId) {
        Instant now = clock.instant();
        String code = TelegramLinkCode.generate();

        jdbc.sql("""
                INSERT INTO integration.telegram_pending_links (
                    id, tenant_id, code, audience, brand_id, expires_at, created_at)
                VALUES (:id, :tenantId, :code, 'AUTH', :brandId, :expiresAt, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", code)
                .param("brandId", brandId)
                .param("expiresAt", utc(now.plus(codeTtl)))
                .param("now", utc(now))
                .update();

        return code;
    }

    /** Looks up an unconsumed, unexpired AUTH code — the {@code /start auth_<code>} entry point. */
    @Transactional(readOnly = true)
    public Optional<PendingAuthLink> resolve(String code) {
        Instant now = clock.instant();
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id
                FROM integration.telegram_pending_links
                WHERE code = :code AND consumed_at IS NULL AND expires_at > :now AND audience = 'AUTH'
                """)
                .param("code", code)
                .param("now", utc(now))
                .query((row, number) -> new PendingAuthLink(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class)))
                .optional();
    }

    /**
     * Marks this chat as the one a {@code request_contact} share is expected to
     * answer this code from. Idempotent by construction — a second
     * {@code /start auth_<code>} for the same still-pending row just overwrites
     * the same value with itself in the ordinary case, or moves it to a new chat
     * if the customer somehow opened the link elsewhere.
     */
    @Transactional
    public void beginAwaitingContact(UUID tenantId, UUID pendingLinkId, long chatId) {
        jdbc.sql("""
                UPDATE integration.telegram_pending_links
                SET awaiting_contact_chat_id = :chatId
                WHERE id = :id AND tenant_id = :tenantId AND consumed_at IS NULL
                """)
                .param("chatId", chatId)
                .param("id", pendingLinkId)
                .param("tenantId", tenantId)
                .update();
    }

    /**
     * The most recent AUTH code this chat is currently answering, if any.
     *
     * <p>The correlation a contact message needs and cannot carry itself:
     * Telegram's {@code request_contact} button has no room for an opaque
     * payload the way an inline button's {@code callback_data} does, so what a
     * {@code contact} message is <em>answering</em> has to be read back from
     * server-side chat state instead. Most-recent-first, so a customer who opened
     * the deep link twice before sharing is answered against the code they most
     * recently asked for.
     */
    @Transactional(readOnly = true)
    public Optional<PendingAuthLink> resolveAwaitingContact(UUID tenantId, long chatId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id
                FROM integration.telegram_pending_links
                WHERE tenant_id = :tenantId AND awaiting_contact_chat_id = :chatId
                  AND consumed_at IS NULL AND expires_at > :now AND audience = 'AUTH'
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("now", utc(now))
                .query((row, number) -> new PendingAuthLink(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class)))
                .optional();
    }

    /**
     * Records which account the contact share resolved to, and the wave-7
     * binding it produced, in the same stroke — {@link TelegramCustomerLinkService#consume}'s
     * own shape, generalised to carry the account this handshake discovers
     * rather than one the caller already knew.
     *
     * @return false when another delivery already redeemed this code (ADR 0032's
     *         at-least-once guarantee — {@code TelegramUpdateDedupStore} already
     *         filters an exact update replay before this is ever reached, so this
     *         is the backstop for the case that dedup does not cover, not the
     *         primary defence)
     */
    @Transactional
    public boolean redeem(UUID tenantId, UUID pendingLinkId, UUID accountId, boolean accountCreated, UUID bindingId) {
        return jdbc.sql("""
                UPDATE integration.telegram_pending_links
                SET consumed_at = :now, created_binding_id = :bindingId,
                    auth_account_id = :accountId, auth_account_created = :accountCreated
                WHERE id = :id AND tenant_id = :tenantId AND consumed_at IS NULL
                """)
                        .param("now", utc(clock.instant()))
                        .param("bindingId", bindingId)
                        .param("accountId", accountId)
                        .param("accountCreated", accountCreated)
                        .param("id", pendingLinkId)
                        .param("tenantId", tenantId)
                        .update()
                == 1;
    }

    /**
     * The storefront poll's one claim.
     *
     * <p>Never returns a session — this class holds nothing recoverable, per its
     * own class doc — only the account to mint one for, and only once:
     * {@code auth_session_claimed_at} is set by a conditional {@code UPDATE} that
     * exactly one caller wins, the same "the condition is the rule" discipline
     * {@code VerificationChallengeStore}'s own doc states for the SMS side's
     * single-use grant. A losing caller (a retried request, a second tab) sees
     * {@link ClaimResult.AlreadyClaimed}; the winner is the one that actually
     * mints a session, in {@code customers.api.CustomerTelegramSignIn#establishSession}.
     */
    @Transactional
    public ClaimResult claimSession(UUID tenantId, UUID brandId, String code) {
        Instant now = clock.instant();

        Optional<PollRow> found = jdbc.sql("""
                SELECT id, consumed_at, expires_at, auth_account_id, auth_account_created, auth_session_claimed_at
                FROM integration.telegram_pending_links
                WHERE code = :code AND tenant_id = :tenantId AND brand_id = :brandId AND audience = 'AUTH'
                """)
                .param("code", code)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, n) -> new PollRow(
                        row.getObject("id", UUID.class),
                        Optional.ofNullable(row.getObject("consumed_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getObject("auth_account_id", UUID.class),
                        row.getObject("auth_account_created", Boolean.class),
                        Optional.ofNullable(row.getObject("auth_session_claimed_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null)))
                .optional();

        if (found.isEmpty()) {
            return ClaimResult.unknown();
        }
        PollRow row = found.get();

        if (row.consumedAt == null) {
            return row.expiresAt.isAfter(now) ? ClaimResult.pending() : ClaimResult.expired();
        }
        if (row.authSessionClaimedAt != null) {
            return ClaimResult.alreadyClaimed();
        }

        int claimed = jdbc.sql("""
                UPDATE integration.telegram_pending_links
                SET auth_session_claimed_at = :now
                WHERE id = :id AND auth_session_claimed_at IS NULL
                """).param("now", utc(now)).param("id", row.id).update();

        if (claimed == 0) {
            // Lost the race against a concurrent poll between the read above and
            // this UPDATE. Extremely rare — the wave-14 idiom's polling browser
            // stops on its own first success — and answered the same as any other
            // already-claimed read.
            return ClaimResult.alreadyClaimed();
        }
        // Non-null by the row's own shape: reaching here means consumedAt was
        // set, and ck_telegram_pending_link_auth_account_pair (V0118) makes
        // auth_account_id and auth_account_created travel together with every
        // redemption -- TelegramAuthLinkService#redeem is the only writer of
        // either, and it always sets both in the same UPDATE.
        UUID accountId = java.util.Objects.requireNonNull(
                row.authAccountId, "A consumed AUTH row must carry a resolved account id");
        return ClaimResult.ready(accountId, Boolean.TRUE.equals(row.authAccountCreated));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record PendingAuthLink(UUID id, UUID tenantId, UUID brandId) {}

    private record PollRow(
            UUID id,
            @Nullable Instant consumedAt,
            Instant expiresAt,
            @Nullable UUID authAccountId,
            @Nullable Boolean authAccountCreated,
            @Nullable Instant authSessionClaimedAt) {}

    /** What a storefront poll learns about one AUTH code. */
    public sealed interface ClaimResult {

        record Unknown() implements ClaimResult {}

        record Pending() implements ClaimResult {}

        record Expired() implements ClaimResult {}

        /** Redeemed, but a session was already minted for it — by an earlier poll, or a losing race with one. */
        record AlreadyClaimed() implements ClaimResult {}

        record Ready(UUID accountId, boolean accountCreated) implements ClaimResult {}

        static ClaimResult unknown() {
            return new Unknown();
        }

        static ClaimResult pending() {
            return new Pending();
        }

        static ClaimResult expired() {
            return new Expired();
        }

        static ClaimResult alreadyClaimed() {
            return new AlreadyClaimed();
        }

        static ClaimResult ready(UUID accountId, boolean accountCreated) {
            return new Ready(accountId, accountCreated);
        }
    }
}
