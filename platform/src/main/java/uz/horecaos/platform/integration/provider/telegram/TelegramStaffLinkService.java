package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The server side of the staff identity {@code /link <code>} handshake
 * (ADR 0060 §3) — a different code, a different table, and a different
 * redemption chat from ADR 0058's group-linking {@link TelegramLinkService}:
 * this one binds a Telegram account to a principal, is issued by that same
 * principal for themselves, and is redeemed only in a 1:1 chat with the bot.
 *
 * <p>Two halves kept apart the same way {@link TelegramLinkService} keeps its
 * own: {@link #issueCode} runs from an authenticated request and never
 * touches Telegram; {@link #resolve} and {@link #link} run from an
 * unauthenticated webhook update and trust nothing about the caller except
 * the code itself.
 */
@Service
public class TelegramStaffLinkService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration codeTtl;

    public TelegramStaffLinkService(
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.notifications.telegram.staff-link-code-ttl:PT15M}") Duration codeTtl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.codeTtl = codeTtl;
    }

    /** Issues a short-lived code the requesting principal pastes as {@code /link <code>} in a 1:1 chat with the bot. */
    @Transactional
    public String issueCode(UUID tenantId, String principalSubject) {
        Instant now = clock.instant();
        String code = TelegramLinkCode.generate();

        jdbc.sql("""
                INSERT INTO integration.telegram_staff_link_codes (id, tenant_id, code, principal_subject, expires_at, created_at)
                VALUES (:id, :tenantId, :code, :subject, :expiresAt, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", code)
                .param("subject", principalSubject)
                .param("expiresAt", utc(now.plus(codeTtl)))
                .param("now", utc(now))
                .update();

        return code;
    }

    /** Looks up an unconsumed, unexpired code, the same not-found-for-everything-wrong answer {@link TelegramLinkService#resolve} gives. */
    @Transactional(readOnly = true)
    public Optional<PendingStaffLink> resolve(String code) {
        Instant now = clock.instant();
        return jdbc.sql("""
                SELECT id, tenant_id, principal_subject
                FROM integration.telegram_staff_link_codes
                WHERE code = :code AND consumed_at IS NULL AND expires_at > :now
                """)
                .param("code", code)
                .param("now", utc(now))
                .query((row, number) -> new PendingStaffLink(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("principal_subject")))
                .optional();
    }

    /**
     * Spends the code and creates the link, atomically with each other. The
     * link itself is an upsert: redeeming the same code twice (a duplicate
     * webhook delivery) or linking the same account to the same principal a
     * second time changes nothing new.
     */
    @Transactional
    public UUID link(UUID tenantId, UUID pendingLinkId, String principalSubject, long telegramUserId) {
        Instant now = clock.instant();
        UUID linkId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO integration.telegram_staff_links (
                    id, tenant_id, telegram_user_id, principal_subject, linked_via_code_id, created_at, updated_at)
                VALUES (:id, :tenantId, :telegramUserId, :subject, :codeId, :now, :now)
                ON CONFLICT (tenant_id, telegram_user_id, principal_subject) DO UPDATE
                SET updated_at = EXCLUDED.updated_at, version = integration.telegram_staff_links.version + 1
                RETURNING id
                """)
                .param("id", linkId)
                .param("tenantId", tenantId)
                .param("telegramUserId", telegramUserId)
                .param("subject", principalSubject)
                .param("codeId", pendingLinkId)
                .param("now", utc(now))
                .query(UUID.class)
                .single();

        jdbc.sql("""
                UPDATE integration.telegram_staff_link_codes
                SET consumed_at = :now, created_link_id = :linkId
                WHERE id = :id AND tenant_id = :tenantId AND consumed_at IS NULL
                """)
                .param("id", pendingLinkId)
                .param("tenantId", tenantId)
                .param("linkId", linkId)
                .param("now", utc(now))
                .update();

        return linkId;
    }

    /** The principal a Telegram account acts as in exactly one tenant, if it is linked there at all. */
    public Optional<String> principalFor(UUID tenantId, long telegramUserId) {
        return jdbc.sql("""
                SELECT principal_subject FROM integration.telegram_staff_links
                WHERE tenant_id = :tenantId AND telegram_user_id = :userId
                ORDER BY created_at
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("userId", telegramUserId)
                .query(String.class)
                .optional();
    }

    /**
     * Every Telegram link in one tenant, for staff administration
     * (staff-and-access.md's callout that "a staff row's Telegram state is
     * real data worth showing" — the People screen and the person record's
     * Безопасность tab both render this rather than inventing a "last seen"
     * this table does not carry).
     *
     * <p>One principal may in principle hold more than one link (nothing here
     * forbids re-linking from a second Telegram account), so this returns
     * every row rather than collapsing to one-per-subject; a caller that wants
     * "is this person linked at all" checks for any match on
     * {@code principalSubject}.
     */
    public List<StaffLinkView> listForTenant(UUID tenantId) {
        return jdbc.sql("""
                SELECT principal_subject, telegram_user_id, created_at
                FROM integration.telegram_staff_links
                WHERE tenant_id = :tenantId
                ORDER BY created_at
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new StaffLinkView(
                        row.getString("principal_subject"),
                        row.getLong("telegram_user_id"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Every tenant this Telegram account is linked into, for the DM tenant-picker (ADR 0060 §3). */
    public List<TenantLink> tenantsFor(long telegramUserId) {
        return jdbc.sql("""
                SELECT tenant_id, principal_subject FROM integration.telegram_staff_links
                WHERE telegram_user_id = :userId
                ORDER BY created_at
                """)
                .param("userId", telegramUserId)
                .query((row, number) ->
                        new TenantLink(row.getObject("tenant_id", UUID.class), row.getString("principal_subject")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record PendingStaffLink(UUID id, UUID tenantId, String principalSubject) {}

    public record TenantLink(UUID tenantId, String principalSubject) {}

    /** One Telegram account bound to one principal, for the staff administration read. */
    public record StaffLinkView(String principalSubject, long telegramUserId, Instant linkedAt) {}
}
