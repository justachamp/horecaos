package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderDecisionPort;

/**
 * The server-side action record an opaque {@code callback_data} token indexes
 * (ADR 0060 §4, V0106). Nothing signed ever travels in a button; a button
 * carries this store's token and nothing else.
 */
@Repository
public class BotActionTokenStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public BotActionTokenStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The token for one order's one action, minted once and reused on every
     * later render of the same notification (a content-unchanged retry, an
     * edit) — see {@code TelegramChannelAdapter}. Reuse rather than a fresh
     * mint per call is what keeps a physical button's {@code decisionId}
     * stable across a harmless resend, per {@link OrderDecisionPort}'s own
     * idempotency contract.
     */
    @Transactional
    public String mintOrReuseOrderDecisionToken(
            UUID tenantId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            OrderDecisionPort.Action action,
            Instant expiresAt) {
        return mintOrReuseOrderDecisionToken(tenantId, orderId, brandId, locationId, action, null, expiresAt);
    }

    /**
     * The reason-picker's own button (wave 24): a {@code REJECT} token that
     * additionally names which reason this physical button rejects with.
     * Minted once per {@code (order, code)} and reused on a harmless resend,
     * exactly like {@link #mintOrReuseOrderDecisionToken(UUID, UUID, UUID,
     * UUID, OrderDecisionPort.Action, Instant)}'s own reuse contract — reused
     * rather than a fresh mint per render is what keeps a physical button's
     * {@code decisionId} stable.
     */
    @Transactional
    public String mintOrReuseOrderRejectReasonToken(
            UUID tenantId, UUID orderId, UUID brandId, UUID locationId, String rejectReasonCode, Instant expiresAt) {
        return mintOrReuseOrderDecisionToken(
                tenantId, orderId, brandId, locationId, OrderDecisionPort.Action.REJECT, rejectReasonCode, expiresAt);
    }

    private String mintOrReuseOrderDecisionToken(
            UUID tenantId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            OrderDecisionPort.Action action,
            @Nullable String rejectReasonCode,
            Instant expiresAt) {
        Instant now = clock.instant();
        Optional<String> existing = jdbc.sql("""
                SELECT token FROM integration.bot_action_tokens
                WHERE tenant_id = :tenantId AND order_id = :orderId AND decision_action = :action
                  AND reject_reason_code IS NOT DISTINCT FROM :rejectReasonCode
                  AND expires_at > :now
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("action", action.name())
                .param("rejectReasonCode", rejectReasonCode)
                .param("now", utc(now))
                .query(String.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        String token = TelegramLinkCode.generate();
        jdbc.sql("""
                INSERT INTO integration.bot_action_tokens (
                    token, tenant_id, kind, order_id, brand_id, location_id, decision_action,
                    reject_reason_code, expires_at, created_at)
                VALUES (:token, :tenantId, 'ORDER_DECISION', :orderId, :brandId, :locationId, :action,
                    :rejectReasonCode, :expiresAt, :now)
                """)
                .param("token", token)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("action", action.name())
                .param("rejectReasonCode", rejectReasonCode)
                .param("expiresAt", utc(expiresAt))
                .param("now", utc(now))
                .update();
        return token;
    }

    public Optional<OrderDecisionToken> resolveOrderDecision(String token) {
        return jdbc.sql("""
                SELECT tenant_id, order_id, brand_id, location_id, decision_action, reject_reason_code
                FROM integration.bot_action_tokens
                WHERE token = :token AND kind = 'ORDER_DECISION' AND expires_at > :now
                """)
                .param("token", token)
                .param("now", utc(clock.instant()))
                .query((row, number) -> new OrderDecisionToken(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        OrderDecisionPort.Action.valueOf(row.getString("decision_action")),
                        row.getString("reject_reason_code")))
                .optional();
    }

    /** A tenant-picker button, scoped to the one account it was rendered for (ADR 0060 §3). */
    @Transactional
    public String mintTenantSelectToken(
            UUID candidateTenantId,
            long telegramUserId,
            String pendingCommand,
            String pendingArgument,
            Instant expiresAt) {
        String token = TelegramLinkCode.generate();
        jdbc.sql("""
                INSERT INTO integration.bot_action_tokens (
                    token, tenant_id, kind, pending_command, pending_argument, telegram_user_id, expires_at, created_at)
                VALUES (:token, :tenantId, 'TENANT_SELECT', :command, :argument, :userId, :expiresAt, :now)
                """)
                .param("token", token)
                .param("tenantId", candidateTenantId)
                .param("command", pendingCommand)
                .param("argument", pendingArgument.isBlank() ? null : pendingArgument)
                .param("userId", telegramUserId)
                .param("expiresAt", utc(expiresAt))
                .param("now", utc(clock.instant()))
                .update();
        return token;
    }

    /**
     * Redeems a tenant-picker token, scoped to the same Telegram account it
     * was rendered for — a forged tap from a different account must not be
     * able to steer which tenant a resumed command runs against.
     */
    public Optional<TenantSelectToken> resolveTenantSelect(String token, long telegramUserId) {
        return jdbc.sql("""
                SELECT tenant_id, pending_command, pending_argument
                FROM integration.bot_action_tokens
                WHERE token = :token AND kind = 'TENANT_SELECT' AND telegram_user_id = :userId AND expires_at > :now
                """)
                .param("token", token)
                .param("userId", telegramUserId)
                .param("now", utc(clock.instant()))
                .query((row, number) -> new TenantSelectToken(
                        row.getObject("tenant_id", UUID.class),
                        row.getString("pending_command"),
                        row.getString("pending_argument")))
                .optional();
    }

    /**
     * A button on a customer's own message (ADR 0075, V0172).
     *
     * <p>Minted fresh every render rather than reused the way an order-decision
     * token is. That reuse exists so a physical Approve button keeps one stable
     * {@code decisionId} across a harmless resend; a customer button has no such
     * contract, and reuse would instead mean a Repeat button rendered last week
     * and one rendered today were the same token — so tapping the old one would
     * silently act on the newer render's intent.
     *
     * <p>Scoped to the Telegram account it was rendered for, exactly as {@link
     * #mintTenantSelectToken} is: the message may be forwarded, and a stranger's
     * tap must not spend this customer's money.
     */
    @Transactional
    public String mintCustomerActionToken(
            UUID tenantId,
            UUID brandId,
            long telegramUserId,
            String action,
            @Nullable UUID orderId,
            @Nullable String argument,
            Instant expiresAt) {
        String token = TelegramLinkCode.generate();
        jdbc.sql("""
                INSERT INTO integration.bot_action_tokens (
                    token, tenant_id, kind, brand_id, order_id, pending_command, pending_argument,
                    telegram_user_id, expires_at, created_at)
                VALUES (:token, :tenantId, 'CUSTOMER_ACTION', :brandId, :orderId, :action, :argument,
                    :userId, :expiresAt, :now)
                """)
                .param("token", token)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("orderId", orderId)
                .param("action", action)
                .param("argument", argument)
                .param("userId", telegramUserId)
                .param("expiresAt", utc(expiresAt))
                .param("now", utc(clock.instant()))
                .update();
        return token;
    }

    /**
     * Redeems a customer action token, scoped to the account it was rendered
     * for and to the chat's own tenant.
     *
     * <p>The {@code telegram_user_id} predicate is inside the query rather than
     * a check on the result: a forwarded message's button tapped by somebody
     * else must resolve to nothing at all, so the tapper cannot learn that the
     * token was real.
     */
    public Optional<CustomerActionToken> resolveCustomerAction(String token, long telegramUserId) {
        return jdbc.sql("""
                SELECT tenant_id, brand_id, order_id, pending_command, pending_argument
                FROM integration.bot_action_tokens
                WHERE token = :token AND kind = 'CUSTOMER_ACTION'
                  AND telegram_user_id = :userId AND expires_at > :now
                """)
                .param("token", token)
                .param("userId", telegramUserId)
                .param("now", utc(clock.instant()))
                .query((row, number) -> new CustomerActionToken(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getString("pending_command"),
                        row.getString("pending_argument")))
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** @param rejectReasonCode null on an APPROVE token and on the bare "pick a reason" REJECT token (wave 24) */
    public record OrderDecisionToken(
            UUID tenantId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            OrderDecisionPort.Action action,
            @Nullable String rejectReasonCode) {}

    /**
     * @param orderId set for STATUS, REPEAT and RATE; null for CART and CHECKOUT,
     *     which are about a basket and name no order (V0172's own shape CHECK)
     * @param argument the action's one parameter — a rating value, a payment
     *     method code — or null where it takes none
     */
    public record CustomerActionToken(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID orderId,
            String action,
            @Nullable String argument) {}

    public record TenantSelectToken(
            UUID tenantId,
            String pendingCommand,
            @org.jspecify.annotations.Nullable String pendingArgument) {}
}
