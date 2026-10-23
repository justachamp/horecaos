package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.CourierTemplate;
import uz.horecaos.platform.marketing.api.CourierContactSource;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierBroadcastRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.RecipientRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A dispatcher's own operational SMS to a courier group or to every active courier (V0307, V0672,
 * operations §6.4b; ADR 0182, D15): a shift change, a weather closure, a route closure. Never a
 * customer campaign, and not a marketing audience of any kind.
 *
 * <p><strong>The wording is an approved template.</strong> A broadcast names an SMS template of the
 * class {@code COURIER_DISPATCH} that the tenant authored and the gateway approved (ADR 0091), and
 * gives the values of the variables the template declares. Free text is gone: the gateway moderates
 * wordings in advance, so text typed into a form could never have been approved.
 *
 * <p><strong>A send is one intent and one ledger row per courier.</strong> It asks the identity
 * provider, once, which of the targeted couriers have a number the platform sends to
 * ({@link CourierContactSource#addressable}); creates an ordinary ADR 0020 intent for each that
 * does; and records the rest as {@code NO_CONTACT}. The number itself is read again, per message,
 * when the message is sent, and is kept nowhere. What the gateway then says about each message is
 * read from the notification's own evidence when a screen asks, never copied here.
 *
 * <p><strong>Every refusal is visible and none is a quiet success.</strong> A send is refused, with a
 * stable code, when the brand's gateway account is not cleared for courier traffic, when the owner
 * has not recorded that a courier's number may be used, when the wording is missing or still
 * awaiting the gateway's approval, when the target has no active courier, when nobody has a number,
 * and when the identity provider cannot be asked. The last leaves the broadcast a draft: a fleet is
 * never recorded as having no number because a server was down.
 *
 * <p>Not transactional as a whole: the identity-provider call sits between two database steps, and a
 * pooled connection is never held across a call to something we do not control. The writes are one
 * transaction in {@link CourierBroadcastLedger}.
 */
@Service
public class CourierBroadcastService {

    /** The one channel V0307's own CHECK allows today. */
    private static final String CHANNEL = "SMS";

    /** The stable code for a broadcast nobody can be addressed for (see {@link CourierContactSource}). */
    public static final String COURIERS_NOT_ADDRESSABLE = "COURIER_CONTACTS_NOT_WIRED";

    /** The identity provider could not be asked, so the send did not happen and the broadcast is still a draft. */
    public static final String CONTACTS_UNAVAILABLE = "COURIER_CONTACTS_UNAVAILABLE";

    /** The target resolves to no active courier. */
    public static final String NO_ACTIVE_COURIER = "NO_ACTIVE_COURIER";

    /** Every targeted courier lacks a number the platform sends to. */
    public static final String NO_COURIER_HAS_A_NUMBER = "NO_COURIER_HAS_A_NUMBER";

    /** The named wording is not an active courier wording for this brand any more. */
    public static final String TEMPLATE_NOT_AVAILABLE = "COURIER_TEMPLATE_NOT_AVAILABLE";

    /** The named wording has not been approved by the SMS gateway (ADR 0091). */
    public static final String TEMPLATE_AWAITING_PROVIDER = "TEMPLATE_AWAITING_PROVIDER";

    /** The purpose recorded when the platform asks which couriers have a number. */
    static final String ADDRESSABILITY_PURPOSE = "COURIER_BROADCAST_SEND";

    /** The longest value a dispatcher gives one variable. */
    public static final int MAXIMUM_VALUE_LENGTH = 80;

    private final JdbcCourierBroadcastStore store;
    private final CampaignMessagePort messages;
    private final CourierContactSource couriers;
    private final CourierBroadcastLedger ledger;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CourierBroadcastService(
            JdbcCourierBroadcastStore store,
            CampaignMessagePort messages,
            CourierContactSource couriers,
            CourierBroadcastLedger ledger,
            AuditRecorder audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.store = store;
        this.messages = messages;
        this.couriers = couriers;
        this.ledger = ledger;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Drafts a broadcast from an approved wording.
     *
     * @param variables the values for the variables the wording declares: exactly those, each a short
     *                  single-line string. A value is the dispatcher's own operational text; a person's
     *                  name or number does not belong in one, and the console says so
     */
    @Transactional
    public UUID draft(
            UUID tenantId,
            UUID brandId,
            String targetKind,
            @Nullable UUID targetGroupId,
            String templateKey,
            @Nullable Map<String, String> variables,
            UUID authorId) {
        if (!"ALL_ACTIVE".equals(targetKind) && !"GROUP".equals(targetKind)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "targetKind must be ALL_ACTIVE or GROUP");
        }
        if ("GROUP".equals(targetKind) == (targetGroupId == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A GROUP broadcast names a targetGroupId; ALL_ACTIVE carries none");
        }
        if (templateKey == null || templateKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "templateKey must name an approved courier wording");
        }
        CourierTemplate template = courierTemplate(tenantId, brandId, templateKey)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "No active courier wording is named %s for this brand".formatted(templateKey),
                        Map.of("reason", TEMPLATE_NOT_AVAILABLE)));
        Map<String, String> values = checked(template, variables == null ? Map.of() : variables);

        UUID id = Ids.newId();
        Instant now = clock.instant();
        store.insert(
                id,
                tenantId,
                brandId,
                targetKind,
                targetGroupId,
                templateKey,
                objectMapper.writeValueAsString(values),
                authorId,
                now);

        audit.record(AuditFact.of("MARKETING_COURIER_BROADCAST_DRAFTED", AuditClass.BUSINESS)
                .by(ActorRef.user(authorId.toString(), null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("CourierBroadcast", id)
                .because("Drafted a courier broadcast")
                // Staff 9.3a: a freshly inserted broadcast has no prior state. The values are the
                // dispatcher's text and stay out of the audit record; the wording's key is enough.
                .changed(ChangeDocuments.created(Map.of("targetKind", targetKind, "templateKey", templateKey)))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return id;
    }

    /**
     * Resolves the target and sends, or refuses visibly (ADR 0182).
     *
     * @throws ApiException {@code UNPROCESSABLE_STATE} with the stable code in the sentence and the
     *         broadcast recorded {@code FAILED}, for a refusal that will not change until somebody
     *         changes something; {@code IDENTITY_PROVIDER_UNAVAILABLE} with the broadcast left a
     *         draft, for one that may clear by itself
     */
    public CourierBroadcastRow send(UUID tenantId, UUID broadcastId, UUID actorId) {
        CourierBroadcastRow broadcast = require(tenantId, broadcastId);
        if (!"DRAFT".equals(broadcast.status())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This broadcast is not a draft, so it cannot be sent");
        }
        Instant now = clock.instant();

        // Both halves of "can this reach a courier": the brand's gateway account is cleared to carry
        // courier traffic (the same answer a campaign reads, ADR 0146 Decision 8), and the owner has
        // said a courier's own number may be used for a dispatcher's message.
        CampaignMessagePort.Wiring wiring =
                messages.wiring(tenantId, broadcast.brandId(), CHANNEL, CampaignMessagePort.PURPOSE_COURIER);
        String refusal = !wiring.isWired() ? wiring.reason() : couriers.isWired() ? null : COURIERS_NOT_ADDRESSABLE;
        if (refusal != null) {
            throw refused(tenantId, broadcast, actorId, refusal, now);
        }

        String templateKey = broadcast.templateKey();
        if (templateKey == null) {
            // A broadcast drafted before V0672 with free text, which the gateway cannot have approved.
            throw refused(tenantId, broadcast, actorId, TEMPLATE_NOT_AVAILABLE, now);
        }
        Map<String, String> values = broadcast.variables();
        CourierTemplate template = courierTemplate(tenantId, broadcast.brandId(), templateKey)
                .orElseThrow(() -> refused(tenantId, broadcast, actorId, TEMPLATE_NOT_AVAILABLE, now));
        if (template.awaitingGateway()) {
            throw refused(tenantId, broadcast, actorId, TEMPLATE_AWAITING_PROVIDER, now);
        }
        // The wording may have been edited since the draft: its variables must still be exactly these.
        try {
            checked(template, values);
        } catch (ApiException changed) {
            throw refused(tenantId, broadcast, actorId, TEMPLATE_NOT_AVAILABLE, now);
        }

        List<JdbcCourierBroadcastStore.CourierTarget> targets =
                store.targetCouriers(tenantId, broadcast.targetKind(), broadcast.targetGroupId());
        if (targets.isEmpty()) {
            throw refused(tenantId, broadcast, actorId, NO_ACTIVE_COURIER, now);
        }

        // The one call to the identity provider. No transaction is open across it.
        Set<UUID> addressable;
        try {
            addressable = couriers.addressable(
                    tenantId,
                    targets.stream()
                            .map(JdbcCourierBroadcastStore.CourierTarget::courierId)
                            .toList(),
                    ADDRESSABILITY_PURPOSE);
        } catch (CourierContactSource.UnavailableException unavailable) {
            audit.record(AuditFact.of("MARKETING_COURIER_BROADCAST_SEND_FAILED", AuditClass.BUSINESS)
                    .by(ActorRef.user(actorId.toString(), null))
                    .at(ResourceScope.brand(tenantId, broadcast.brandId()))
                    .target("CourierBroadcast", broadcast.id())
                    .outcome(AuditFact.Outcome.FAILED)
                    .because(
                            "The identity provider could not be asked for couriers' numbers (%s); the broadcast is still a draft"
                                    .formatted(CONTACTS_UNAVAILABLE))
                    .correlatedBy(correlationId())
                    .occurredAt(now)
                    .build());
            throw new ApiException(
                    ErrorCode.IDENTITY_PROVIDER_UNAVAILABLE,
                    "Couriers' numbers could not be read (%s); nothing was sent and the broadcast is still a draft"
                            .formatted(CONTACTS_UNAVAILABLE),
                    Map.of("reason", CONTACTS_UNAVAILABLE));
        }

        CourierBroadcastLedger.Outcome outcome = ledger.enqueue(broadcast, targets, addressable, values, actorId, now);
        if (outcome.queued() == 0) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "No courier of this target has a number the platform sends to (%s); nothing was sent"
                            .formatted(NO_COURIER_HAS_A_NUMBER));
        }
        return outcome.broadcast();
    }

    /**
     * What happened to each courier a broadcast targeted: who was messaged, with the evidence of that
     * message, and who had nothing to be messaged on.
     */
    @Transactional(readOnly = true)
    public List<CourierResult> results(UUID tenantId, UUID broadcastId) {
        List<RecipientRow> rows = store.recipients(tenantId, broadcastId);
        Map<UUID, CampaignMessagePort.DeliveryEvidence> evidence = messages.deliveryEvidence(
                tenantId,
                rows.stream()
                        .map(RecipientRow::notificationId)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        List<CourierResult> results = new ArrayList<>();
        for (RecipientRow row : rows) {
            if ("NO_CONTACT".equals(row.state())) {
                results.add(
                        new CourierResult(row.courierReference(), "NO_CONTACT", "COURIER_HAS_NO_NUMBER", null, null));
                continue;
            }
            CampaignMessagePort.DeliveryEvidence delivery =
                    row.notificationId() == null ? null : evidence.get(row.notificationId());
            results.add(
                    delivery == null
                            ? new CourierResult(
                                    row.courierReference(), CampaignMessagePort.STATE_PENDING, null, null, null)
                            : new CourierResult(
                                    row.courierReference(),
                                    delivery.state(),
                                    delivery.reason(),
                                    delivery.receiptState(),
                                    delivery.segmentsBilled()));
        }
        return results;
    }

    /**
     * The wordings a dispatcher may broadcast, and whether the brand can send courier SMS at all and
     * if not why (ADR 0182): read before drafting, so the console says it in a sentence rather than a
     * dispatcher discovering it at the send.
     */
    @Transactional(readOnly = true)
    public Readiness readiness(UUID tenantId, UUID brandId) {
        CampaignMessagePort.Wiring wiring =
                messages.wiring(tenantId, brandId, CHANNEL, CampaignMessagePort.PURPOSE_COURIER);
        String reason = !wiring.isWired() ? wiring.reason() : couriers.isWired() ? null : COURIERS_NOT_ADDRESSABLE;
        return new Readiness(reason == null, reason, messages.courierTemplates(tenantId, brandId));
    }

    @Transactional(readOnly = true)
    public List<CourierBroadcastRow> list(UUID tenantId, UUID brandId) {
        return store.listByBrand(tenantId, brandId);
    }

    public CourierBroadcastRow require(UUID tenantId, UUID broadcastId) {
        return store.find(tenantId, broadcastId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "No courier broadcast %s belongs to this tenant".formatted(broadcastId)));
    }

    // --------------------------------------------------------------------------- internals

    private java.util.Optional<CourierTemplate> courierTemplate(UUID tenantId, UUID brandId, String templateKey) {
        return messages.courierTemplates(tenantId, brandId).stream()
                .filter(template -> template.templateKey().equals(templateKey))
                .findFirst();
    }

    /**
     * The values a dispatcher gave, checked against the variables the wording declares: all of them and
     * only them, each short and on one line. Anything else could be a message the wording does not say.
     */
    private static Map<String, String> checked(CourierTemplate template, Map<String, String> given) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : template.variables()) {
            String value = given.get(name);
            if (value == null || value.isBlank()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "The wording needs a value for \"%s\"".formatted(name));
            }
            String trimmed = value.strip();
            if (trimmed.length() > MAXIMUM_VALUE_LENGTH) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "\"%s\" must be %d characters or fewer".formatted(name, MAXIMUM_VALUE_LENGTH));
            }
            if (trimmed.chars().anyMatch(Character::isISOControl)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "\"%s\" must be a single line of text".formatted(name));
            }
            values.put(name, trimmed);
        }
        for (String name : given.keySet()) {
            if (!values.containsKey(name)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "The wording has no variable \"%s\"".formatted(name));
            }
        }
        return values;
    }

    /** Records the refusal on the broadcast and in the audit trail, and builds the error to throw. */
    private ApiException refused(UUID tenantId, CourierBroadcastRow broadcast, UUID actorId, String code, Instant now) {
        String reason = "No SMS delivery path can reach couriers for this brand (%s)".formatted(code);
        store.recordFailed(tenantId, broadcast.id(), reason, now);
        audit.record(AuditFact.of("MARKETING_COURIER_BROADCAST_SEND_FAILED", AuditClass.BUSINESS)
                .by(ActorRef.user(actorId.toString(), null))
                .at(ResourceScope.brand(tenantId, broadcast.brandId()))
                .target("CourierBroadcast", broadcast.id())
                .outcome(AuditFact.Outcome.FAILED)
                .because(reason)
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return new ApiException(ErrorCode.UNPROCESSABLE_STATE, reason);
    }

    /** Same MDC-or-random fallback {@code ServiceScheduleService} uses for an audit fact with no request-scoped id. */
    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    /**
     * One courier's answer after a send.
     *
     * @param courierReference the courier's non-personal handle ({@code K-014}), never a name or a number
     * @param state the delivery word the evidence earns (see {@link CampaignMessagePort.DeliveryEvidence}),
     *              or {@code NO_CONTACT} for a courier with no number
     * @param reason a bounded code that says why, or null
     */
    public record CourierResult(
            String courierReference,
            String state,
            @Nullable String reason,
            @Nullable String receiptState,
            @Nullable Integer segmentsBilled) {}

    /** Whether courier SMS can leave for a brand, why not, and the wordings a dispatcher may name. */
    public record Readiness(boolean isWired, @Nullable String reason, List<CourierTemplate> templates) {}
}
