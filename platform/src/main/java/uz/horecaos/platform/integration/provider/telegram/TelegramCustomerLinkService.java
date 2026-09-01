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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.notifications.api.CustomerProviderBindingSync;

/**
 * The server side of the customer 1:1 linking handshake (ADR 0058 stage 2): a
 * {@code /start <code>} deep link, or a verified Mini App {@code initData}
 * handshake with no code at all. Mirrors {@link TelegramLinkService}'s own
 * two-halves discipline — {@link #issueCode} runs from an authenticated
 * storefront request (the customer's own ADR 0051 session) and never touches
 * Telegram; {@link #resolve}, {@link #consume} and {@link #link} run from an
 * unauthenticated webhook update, or from a verified {@code initData}
 * payload, and trust nothing about the caller beyond what was already
 * verified.
 *
 * <p>Unlike the staff identity link (ADR 0060 §3, {@link TelegramStaffLinkService}),
 * which is a bare identity fact with no chat subscription behind it, this
 * handshake creates a real ADR 0026 binding: a customer's own chat must
 * actually receive fanned-out transactional messages. {@link #link} keeps the
 * binding and its {@code notifications.recipient_endpoints} row together, via
 * {@link CustomerProviderBindingSync}, the same "written and retired
 * together" discipline {@link TelegramBindingStore}'s own class javadoc
 * states for the binding pair itself.
 */
@Service
public class TelegramCustomerLinkService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration codeTtl;
    private final TelegramBindingStore bindings;
    private final CustomerProviderBindingSync endpointSync;
    private final AuditRecorder audit;

    public TelegramCustomerLinkService(
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.notifications.telegram.customer-link-code-ttl:PT15M}") Duration codeTtl,
            TelegramBindingStore bindings,
            CustomerProviderBindingSync endpointSync,
            AuditRecorder audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.codeTtl = codeTtl;
        this.bindings = bindings;
        this.endpointSync = endpointSync;
        this.audit = audit;
    }

    /**
     * Mints the code behind {@code https://t.me/<bot>?start=<code>}, issued
     * from the customer's own storefront session. 16 URL-safe characters
     * ({@link TelegramLinkCode}), well under ADR 0044's 64-character {@code
     * /start} payload bound.
     */
    @Transactional
    public String issueCode(UUID tenantId, UUID brandId, UUID customerAccountId) {
        Instant now = clock.instant();
        String code = TelegramLinkCode.generate();

        jdbc.sql("""
                INSERT INTO integration.telegram_pending_links (
                    id, tenant_id, code, audience, brand_id, customer_account_id, expires_at, created_at)
                VALUES (:id, :tenantId, :code, 'CUSTOMER', :brandId, :customerAccountId, :expiresAt, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", code)
                .param("brandId", brandId)
                .param("customerAccountId", customerAccountId)
                .param("expiresAt", utc(now.plus(codeTtl)))
                .param("now", utc(now))
                .update();

        return code;
    }

    /**
     * Looks up an unconsumed, unexpired CUSTOMER code — the same
     * not-found-for-everything-wrong answer {@link TelegramLinkService#resolve}
     * gives, scoped to {@code audience = 'CUSTOMER'} so a code from this
     * handshake is never mistaken for a group- or staff-link code sharing the
     * same table.
     */
    @Transactional(readOnly = true)
    public Optional<PendingCustomerLink> resolve(String code) {
        Instant now = clock.instant();
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, customer_account_id
                FROM integration.telegram_pending_links
                WHERE code = :code AND consumed_at IS NULL AND expires_at > :now
                  AND audience = 'CUSTOMER'
                """)
                .param("code", code)
                .param("now", utc(now))
                .query((row, number) -> new PendingCustomerLink(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class)))
                .optional();
    }

    /**
     * Marks a code spent, once, atomically with recording which binding it
     * produced — {@link TelegramLinkService#consume}'s own shape.
     *
     * @return false when another delivery already consumed this code (ADR
     *         0032's at-least-once guarantee), which the caller treats as
     *         success — the binding it created stands
     */
    @Transactional
    public boolean consume(UUID tenantId, UUID pendingLinkId, UUID createdBindingId) {
        return jdbc.sql("""
                UPDATE integration.telegram_pending_links
                SET consumed_at = :now, created_binding_id = :bindingId
                WHERE id = :id AND tenant_id = :tenantId AND consumed_at IS NULL
                """)
                        .param("id", pendingLinkId)
                        .param("tenantId", tenantId)
                        .param("bindingId", createdBindingId)
                        .param("now", utc(clock.instant()))
                        .update()
                == 1;
    }

    /**
     * Creates the CUSTOMER-audience binding and its endpoint together — the
     * shared step both the {@code /start} redemption and the Mini App
     * one-step path call, so the two never diverge in what "linked" means.
     *
     * <p>Idempotent by account rather than by chat: a customer who already
     * has an active link (a second code redeemed before the first, or a
     * repeated Mini App call) gets the existing binding back rather than a
     * second one — the natural failure this guards is {@code
     * ux_telegram_binding_chat}'s own partial unique index rejecting a second
     * row for the same chat with a raw constraint violation instead of a
     * graceful answer. Switching chats requires unlinking first
     * ({@link #unlink}); this method never retires an existing link on the
     * caller's behalf.
     */
    @Transactional
    public UUID link(
            UUID tenantId,
            UUID installationId,
            UUID brandId,
            UUID customerAccountId,
            long chatId,
            long telegramUserId,
            Instant now) {
        Optional<UUID> existing = endpointSync.activeBindingFor(tenantId, customerAccountId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID bindingId = bindings.createBinding(
                tenantId, installationId, brandId, null, chatId, null, telegramUserId, "CUSTOMER");
        endpointSync.onCustomerBindingLinked(tenantId, bindingId, customerAccountId, now);

        // ADR 0026: binding activation is an ADR 0027 audit fact. The actor is
        // the customer themselves — nobody else can create this binding, and
        // an ADR 0051 session has no Keycloak subject to name instead.
        audit.record(AuditFact.of("integration.telegram_customer_binding_created", AuditClass.SECURITY)
                .by(ActorRef.user(customerAccountId.toString(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("IntegrationBinding", bindingId)
                .because("Telegram customer-link handshake completed")
                .correlatedBy(bindingId.toString())
                .occurredAt(now)
                .build());

        return bindingId;
    }

    /**
     * The import counterpart to {@link #link} (ADR 0059 stage 3): a chat
     * already known to this exact bot through a SendPulse contact-export row,
     * rather than one that just completed the {@code /start} handshake.
     *
     * <p>Idempotent the same way {@link #link} is — by customer account, not
     * by chat — which is deliberate rather than a gap: the caller ({@code
     * SendPulseContactImportRowService}) has already resolved "does this chat
     * already belong to a customer account" through {@link
     * TelegramBindingStore#customerAccountFor} before ever reaching this
     * method, so a genuinely new chat reaches here holding a
     * {@code customerAccountId} that cannot yet have an active binding except
     * through a race this check still catches. {@code ux_telegram_binding_chat}
     * remains the chat-level backstop either way.
     *
     * @param subscribed the row's interpreted SendPulse subscription status,
     *                   passed straight to {@link CustomerProviderBindingSync#onCustomerBindingImported}
     * @param importedBySubject the Keycloak subject who ran the import — the
     *                   audit actor, unlike {@link #link}'s own binding
     *                   creation where the customer is necessarily the actor
     */
    @Transactional
    public UUID importLink(
            UUID tenantId,
            UUID installationId,
            UUID brandId,
            UUID customerAccountId,
            long chatId,
            long telegramUserId,
            boolean subscribed,
            String importedBySubject,
            Instant now) {
        Optional<UUID> existing = endpointSync.activeBindingFor(tenantId, customerAccountId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID bindingId = bindings.createBinding(
                tenantId, installationId, brandId, null, chatId, null, telegramUserId, "CUSTOMER");
        endpointSync.onCustomerBindingImported(tenantId, bindingId, customerAccountId, subscribed, now);

        // ADR 0026: binding activation is an ADR 0027 audit fact, same as
        // link()'s own — the actor here is the operator who ran the import,
        // since an imported chat never completed a handshake of its own to
        // name the customer as the actor.
        audit.record(AuditFact.of("integration.telegram_customer_binding_imported", AuditClass.SECURITY)
                .by(ActorRef.user(importedBySubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("IntegrationBinding", bindingId)
                .because("SendPulse contact export import")
                .correlatedBy(bindingId.toString())
                .occurredAt(now)
                .build());

        return bindingId;
    }

    /** The customer's own active binding, if linked — what a storefront status call answers with. */
    public Optional<UUID> activeBinding(UUID tenantId, UUID customerAccountId) {
        return endpointSync.activeBindingFor(tenantId, customerAccountId);
    }

    /**
     * Retires the customer's own active link, if any. A customer choosing to
     * unlink is not consent revocation-by-403 (that path is {@code
     * TelegramChannelAdapter}'s, via the same {@link CustomerProviderBindingSync#onProviderBindingRetired}
     * call this makes) — but the effect on records is the same either way,
     * and it should be: unlinking is exactly the "records match reality"
     * outcome ADR 0058 asks a 403 to produce, asked for directly instead.
     *
     * @return false when nothing was linked, which the caller answers as a
     *         successful unlink rather than a not-found — unlinking what was
     *         never linked is the state the caller asked for
     */
    @Transactional
    public boolean unlink(UUID tenantId, UUID customerAccountId) {
        Instant now = clock.instant();
        Optional<UUID> bindingId = endpointSync.activeBindingFor(tenantId, customerAccountId);
        if (bindingId.isEmpty()) {
            return false;
        }
        bindings.retire(tenantId, bindingId.get(), "MANUAL");
        endpointSync.onProviderBindingRetired(tenantId, bindingId.get(), "MANUAL", now);
        return true;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record PendingCustomerLink(UUID id, UUID tenantId, UUID brandId, UUID customerAccountId) {}
}
