package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The server side of the {@code /link <code>} handshake (ADR 0058).
 *
 * <p>Two halves, deliberately kept apart. {@link #issueCode} runs from an
 * authenticated operations request and never touches Telegram. {@link #resolve}
 * and {@link #consume} run from an unauthenticated webhook update and never trust
 * anything about the caller except the code itself — the secret-token check that
 * happens before either is called is what earns that update the right to be
 * parsed at all.
 */
@Service
public class TelegramLinkService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration codeTtl;

    public TelegramLinkService(
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.notifications.telegram.link-code-ttl:PT15M}") Duration codeTtl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.codeTtl = codeTtl;
    }

    /** Issues a short-lived code an operator pastes as {@code /link <code>} in the target group. */
    @Transactional
    public String issueCode(UUID tenantId, UUID brandId, UUID locationId, String requestedByPrincipalId) {
        Instant now = clock.instant();
        String code = TelegramLinkCode.generate();

        jdbc.sql("""
                INSERT INTO integration.telegram_pending_links (
                    id, tenant_id, code, audience, brand_id, location_id,
                    requested_by_principal_id, expires_at, created_at)
                VALUES (:id, :tenantId, :code, 'OPERATIONS', :brandId, :locationId,
                    :requestedBy, :expiresAt, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", code)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("requestedBy", requestedByPrincipalId)
                .param("expiresAt", utc(now.plus(codeTtl)))
                .param("now", utc(now))
                .update();

        return code;
    }

    /**
     * Looks up an unconsumed, unexpired code. A group-message handler calls this
     * before it does anything else with a {@code /link} command; anything else —
     * expired, already consumed, never issued — is one answer: not found, so the
     * bot's refusal cannot be used to enumerate which codes exist.
     */
    @Transactional(readOnly = true)
    public Optional<PendingLink> resolve(String code) {
        Instant now = clock.instant();
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, requested_by_principal_id
                FROM integration.telegram_pending_links
                WHERE code = :code AND consumed_at IS NULL AND expires_at > :now
                """)
                .param("code", code)
                .param("now", utc(now))
                .query((row, number) -> new PendingLink(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("requested_by_principal_id")))
                .optional();
    }

    /**
     * Marks a code spent, once, atomically with recording which binding it
     * produced. Conditional on {@code consumed_at IS NULL} so two updates racing
     * the same group (a duplicate webhook delivery, ADR 0032's at-least-once
     * guarantee) cannot both succeed.
     *
     * @return false when another delivery already consumed this code, which the
     *         caller treats as success — the binding it created stands
     */
    @Transactional
    public boolean consume(UUID pendingLinkId, UUID createdBindingId) {
        return jdbc.sql("""
                UPDATE integration.telegram_pending_links
                SET consumed_at = :now, created_binding_id = :bindingId
                WHERE id = :id AND consumed_at IS NULL
                """)
                .param("id", pendingLinkId)
                .param("bindingId", createdBindingId)
                .param("now", utc(clock.instant()))
                .update()
                == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record PendingLink(UUID id, UUID tenantId, UUID brandId, UUID locationId, String requestedByPrincipalId) {}
}
