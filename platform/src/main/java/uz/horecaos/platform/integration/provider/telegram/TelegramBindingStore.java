package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory.ScopedBinding;

/**
 * Telegram bindings: creation through the {@code /link} handshake, the fan-out
 * lookup a trigger uses, and the taxonomy the adapter drives (ADR 0058).
 *
 * <p>The ADR 0026 {@code integration.bindings} row and this table's row are
 * written and retired together, in the same transaction, everywhere either
 * changes — there is deliberately no path that creates or retires one without
 * the other.
 */
@Repository
public class TelegramBindingStore {

    /**
     * The ADR 0026 binding capability a Telegram chat binding declares.
     *
     * <p>Must equal {@code NotificationProviderCapabilityCatalog}'s derivation
     * ({@code "SEND_" + channel}) even though this category's bindings never run
     * through {@link uz.horecaos.platform.integration.provider.ProviderCapabilityReconciliationService}
     * (they activate directly on creation — see {@link #createBinding}) — an
     * operator who later runs a manual capability reconciliation on the Telegram
     * installation must still see a snapshot that actually lines up with what is
     * bound, not a code that silently never matches.
     */
    public static final String SEND_TELEGRAM_MESSAGE = "SEND_TELEGRAM";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditRecorder audit;

    public TelegramBindingStore(JdbcClient jdbc, Clock clock, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Creates the binding pair after the bot has already verified its own rights
     * in the chat (ADR 0058: {@code getChatMember} first, actionable failure if
     * absent, binding only after).
     *
     * <p>{@code is_primary} is false on the capability row and always will be for
     * this category: several chats legitimately want the same event at the same
     * scope, which is exactly what ADR 0026's "one primary per scope and
     * capability" index exists to prevent for a category where only one account
     * should ever answer. Telegram fan-out is read through
     * {@link ProviderInstallationLookup#candidateBindings}, which does not filter
     * on primacy, rather than through {@code primaryBinding}.
     */
    public UUID createBinding(
            UUID tenantId,
            UUID installationId,
            UUID brandId,
            @Nullable UUID locationId,
            long chatId,
            @Nullable Integer topicId,
            @Nullable Long linkedByTelegramUserId) {
        return createBinding(
                tenantId, installationId, brandId, locationId, chatId, topicId, linkedByTelegramUserId, "OPERATIONS");
    }

    /**
     * Creates the binding pair for a given audience.
     *
     * @param audience {@code OPERATIONS} (tenant staff, the stage-1 default) or
     *                 {@code PLATFORM} (control-plane digests, ADR 0058). A
     *                 PLATFORM binding is still created under one tenant row —
     *                 ADR 0026 bindings are tenant-scoped end to end — typically
     *                 the platform's own operating tenant; the audience marks
     *                 how its content is queried, not a different schema shape.
     */
    public UUID createBinding(
            UUID tenantId,
            UUID installationId,
            UUID brandId,
            @Nullable UUID locationId,
            long chatId,
            @Nullable Integer topicId,
            @Nullable Long linkedByTelegramUserId,
            String audience) {
        Instant now = clock.instant();
        UUID bindingId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO integration.bindings (
                    id, tenant_id, installation_id, brand_id, location_id, status, priority,
                    effective_from, created_at, updated_at)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', 100,
                    :now, :now, :now)
                """)
                .param("id", bindingId)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("now", utc(now))
                .update();

        jdbc.sql("""
                INSERT INTO integration.binding_capabilities (binding_id, tenant_id, capability_code, enabled, is_primary)
                VALUES (:bindingId, :tenantId, :capability, true, false)
                """)
                .param("bindingId", bindingId)
                .param("tenantId", tenantId)
                .param("capability", SEND_TELEGRAM_MESSAGE)
                .update();

        jdbc.sql("""
                INSERT INTO integration.telegram_bindings (
                    binding_id, tenant_id, chat_id, topic_id, audience, linked_by_telegram_user_id,
                    created_at, updated_at)
                VALUES (:bindingId, :tenantId, :chatId, :topicId, :audience, :linkedBy, :now, :now)
                """)
                .param("bindingId", bindingId)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("topicId", topicId)
                .param("audience", audience)
                .param("linkedBy", linkedByTelegramUserId)
                .param("now", utc(now))
                .update();

        return bindingId;
    }

    /** Which event classes a binding wants, so the handshake can seed the default subscription set. */
    public void subscribe(UUID tenantId, UUID bindingId, Set<String> eventClasses) {
        for (String eventClass : eventClasses) {
            jdbc.sql("""
                    INSERT INTO integration.telegram_binding_events (binding_id, tenant_id, event_class, enabled)
                    VALUES (:bindingId, :tenantId, :eventClass, true)
                    ON CONFLICT (binding_id, event_class) DO UPDATE SET enabled = true
                    """)
                    .param("bindingId", bindingId)
                    .param("tenantId", tenantId)
                    .param("eventClass", eventClass)
                    .update();
        }
    }

    /**
     * Every active, subscribed chat for one event class at one scope — the fan-out
     * a trigger reads. Narrowest-scope inclusion only: a location's own bindings
     * plus its brand's flat (no-location) bindings, matching
     * {@link ProviderInstallationLookup}'s own scope resolution.
     */
    public List<UUID> subscribedBindings(UUID tenantId, UUID brandId, @Nullable UUID locationId, String eventClass) {
        return jdbc.sql("""
                SELECT b.id
                  FROM integration.bindings b
                  JOIN integration.telegram_bindings tb
                    ON tb.tenant_id = b.tenant_id AND tb.binding_id = b.id
                  JOIN integration.telegram_binding_events tbe
                    ON tbe.tenant_id = b.tenant_id AND tbe.binding_id = b.id
                 WHERE b.tenant_id = :tenantId
                   AND b.status = 'ACTIVE'
                   AND tb.retired_at IS NULL
                   AND tbe.event_class = :eventClass
                   AND tbe.enabled
                   AND (
                        (b.location_id = :locationId)
                     OR (b.location_id IS NULL AND b.brand_id = :brandId)
                   )
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("eventClass", eventClass)
                .query(UUID.class)
                .list();
    }

    /**
     * Every {@code OPERATIONS}-audience chat subscribed to {@code eventClass}
     * anywhere in the tenant, with the scope each was bound at — a digest's
     * fan-out, which has no single order to scope against (ADR 0058).
     */
    public List<ScopedBinding> tenantDigestBindings(UUID tenantId, String eventClass) {
        return jdbc.sql("""
                SELECT b.id, b.brand_id, b.location_id
                  FROM integration.bindings b
                  JOIN integration.telegram_bindings tb
                    ON tb.tenant_id = b.tenant_id AND tb.binding_id = b.id
                  JOIN integration.telegram_binding_events tbe
                    ON tbe.tenant_id = b.tenant_id AND tbe.binding_id = b.id
                 WHERE b.tenant_id = :tenantId
                   AND b.status = 'ACTIVE'
                   AND tb.retired_at IS NULL
                   AND tb.audience = 'OPERATIONS'
                   AND tbe.event_class = :eventClass
                   AND tbe.enabled
                """)
                .param("tenantId", tenantId)
                .param("eventClass", eventClass)
                .query((row, number) -> new ScopedBinding(
                        tenantId,
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class)))
                .list();
    }

    /**
     * Every {@code PLATFORM}-audience chat subscribed to {@code eventClass},
     * platform-wide rather than filtered to one tenant (ADR 0058).
     */
    public List<ScopedBinding> platformDigestBindings(String eventClass) {
        return jdbc.sql("""
                SELECT b.tenant_id, b.id, b.brand_id, b.location_id
                  FROM integration.bindings b
                  JOIN integration.telegram_bindings tb
                    ON tb.tenant_id = b.tenant_id AND tb.binding_id = b.id
                  JOIN integration.telegram_binding_events tbe
                    ON tbe.tenant_id = b.tenant_id AND tbe.binding_id = b.id
                 WHERE b.status = 'ACTIVE'
                   AND tb.retired_at IS NULL
                   AND tb.audience = 'PLATFORM'
                   AND tbe.event_class = :eventClass
                   AND tbe.enabled
                """)
                .param("eventClass", eventClass)
                .query((row, number) -> new ScopedBinding(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class)))
                .list();
    }

    /**
     * The scope a bound chat receives notifications at (ADR 0060 §3): the
     * reverse of {@link #chatFor}, so a typed command arriving in a bound
     * group can resolve its own tenant/brand/location without the caller
     * asking for it out of band. {@code locationId} is null for a brand-flat
     * binding, which a caller that needs one location (a stop-list toggle, a
     * stats query) must treat as unresolved rather than guess at.
     */
    public Optional<BindingScope> scopeForChat(UUID tenantId, long chatId, @Nullable Integer topicId) {
        return jdbc.sql("""
                SELECT b.id AS binding_id, b.brand_id, b.location_id
                FROM integration.bindings b
                JOIN integration.telegram_bindings tb ON tb.tenant_id = b.tenant_id AND tb.binding_id = b.id
                WHERE b.tenant_id = :tenantId AND tb.chat_id = :chatId
                  AND COALESCE(tb.topic_id, -1) = COALESCE(:topicId, -1)
                  AND tb.retired_at IS NULL AND b.status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("topicId", topicId)
                .query((row, number) -> new BindingScope(
                        row.getObject("binding_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class)))
                .optional();
    }

    /**
     * The customer account already linked to this chat, if any (ADR 0058's
     * handshake, ADR 0059's conversations engine reading it). Scoped to
     * {@code CUSTOMER}-audience bindings only — an operations group's chat id
     * is never a customer's.
     */
    public Optional<UUID> customerAccountFor(UUID tenantId, long chatId) {
        return jdbc.sql("""
                SELECT re.customer_account_id
                FROM integration.telegram_bindings tb
                JOIN notifications.recipient_endpoints re
                  ON re.tenant_id = tb.tenant_id AND re.provider_binding_id = tb.binding_id
                WHERE tb.tenant_id = :tenantId AND tb.chat_id = :chatId AND tb.audience = 'CUSTOMER'
                  AND tb.retired_at IS NULL AND re.customer_account_id IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query(UUID.class)
                .optional();
    }

    /** The chat a binding currently points at, for the adapter to call the Bot API with. */
    public Optional<ChatRef> chatFor(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                SELECT chat_id, topic_id FROM integration.telegram_bindings
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND retired_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) ->
                        new ChatRef(bindingId, row.getLong("chat_id"), (Integer) row.getObject("topic_id")))
                .optional();
    }

    /**
     * Retires a binding: 403 (blocked/kicked) or a topic-gone 400 (ADR 0058). Both
     * the taxonomy (here) and the generic ADR 0026 status (SUSPENDED) are set in
     * one statement each, in the same call, so a reconciliation tool reading only
     * {@code integration.bindings} sees the same suspension a Telegram-aware one
     * reading this table explains.
     */
    public void retire(UUID tenantId, UUID bindingId, String reason) {
        Instant now = clock.instant();
        int retired = jdbc.sql("""
                UPDATE integration.telegram_bindings
                SET retired_at = :now, retired_reason = :reason, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND retired_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("reason", reason)
                .param("now", utc(now))
                .update();

        jdbc.sql("""
                UPDATE integration.bindings SET status = 'SUSPENDED', version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :bindingId AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("now", utc(now))
                .update();

        if (retired == 1) {
            // ADR 0026: "binding activation and suspension... are ADR 0027
            // audit facts." The actor is the platform itself — Telegram
            // reported the failure, no operator asked for this — so a system
            // job, not a user, which is also why no .because(...) reason
            // string is required: AuditFact only demands one from a USER actor.
            audit.record(AuditFact.of("integration.telegram_binding_retired", AuditClass.SECURITY)
                    .by(ActorRef.systemJob("telegram-bot-api"))
                    .at(ResourceScope.tenant(tenantId))
                    .target("IntegrationBinding", bindingId)
                    .changed(Map.of("reason", reason))
                    .correlatedBy(bindingId.toString())
                    .occurredAt(now)
                    .build());
        }
    }

    /**
     * Rewrites a binding's chat id after Telegram's {@code migrate_to_chat_id}
     * (ADR 0058: "a group upgraded to a supergroup keeps receiving without
     * operator help").
     *
     * @return false when the binding already points at {@code newChatId} — the
     *         caller's signal that a duplicate migrate_to_chat_id answer arrived
     *         and the send should not be replayed a second time
     */
    public boolean rewriteChatId(UUID tenantId, UUID bindingId, long newChatId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE integration.telegram_bindings
                SET migrated_from_chat_id = chat_id, chat_id = :newChatId,
                    last_migrated_at = :now, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND binding_id = :bindingId
                  AND chat_id <> :newChatId AND retired_at IS NULL
                """)
                        .param("tenantId", tenantId)
                        .param("bindingId", bindingId)
                        .param("newChatId", newChatId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ChatRef(
            UUID bindingId, long chatId, @Nullable Integer topicId) {}

    public record BindingScope(
            UUID bindingId, UUID brandId, @Nullable UUID locationId) {}
}
