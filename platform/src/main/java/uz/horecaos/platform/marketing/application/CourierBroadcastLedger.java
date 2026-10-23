package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.CourierMessage;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierBroadcastRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierTarget;

/**
 * The database half of a courier send (ADR 0182): one transaction that creates an ADR 0020 intent for
 * each courier who can be addressed, writes the ledger, and moves the broadcast to its outcome.
 *
 * <p>A class of its own so {@link CourierBroadcastService} can stay outside any transaction while it
 * asks the identity provider which couriers have a number: a pooled connection is never held across a
 * call to something we do not control, and a proxy only intercepts a call that crosses a bean boundary.
 *
 * <p>Idempotent on the pair (broadcast, courier): the intent's key and the ledger's unique key both
 * name it, so a send repeated by a client that did not see the answer messages nobody twice, and the
 * broadcast's own conditional update lets only the first of two racing sends move it.
 */
@Service
public class CourierBroadcastLedger {

    private final JdbcCourierBroadcastStore store;
    private final CampaignMessagePort messages;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierBroadcastLedger(
            JdbcCourierBroadcastStore store, CampaignMessagePort messages, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.messages = messages;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param addressable the targeted couriers who have a number the platform sends to
     * @return the broadcast as it stands after the send, and how many couriers were queued
     */
    @Transactional
    public Outcome enqueue(
            CourierBroadcastRow broadcast,
            List<CourierTarget> targets,
            Set<UUID> addressable,
            Map<String, String> variables,
            UUID actorId,
            Instant now) {
        UUID tenantId = broadcast.tenantId();
        String templateKey = java.util.Objects.requireNonNull(broadcast.templateKey());
        int queued = 0;
        int noContact = 0;
        for (CourierTarget target : targets) {
            if (!addressable.contains(target.courierId())) {
                store.insertRecipient(
                        tenantId, broadcast.id(), target.courierId(), target.reference(), "NO_CONTACT", null, now);
                noContact++;
                continue;
            }
            UUID notificationId = messages.enqueueCourierMessage(new CourierMessage(
                    tenantId,
                    broadcast.brandId(),
                    target.courierId(),
                    broadcast.id(),
                    templateKey,
                    "courier-broadcast:" + broadcast.id() + ":" + target.courierId(),
                    variables,
                    now,
                    null));
            if (notificationId == null) {
                // The wiring check passed and the delivery path then declined: a fault, not a courier
                // without a number. Rolled back whole, so no ledger row claims a message that does not exist.
                throw new IllegalStateException("No delivery path accepted a courier message for a wired brand");
            }
            store.insertRecipient(
                    tenantId, broadcast.id(), target.courierId(), target.reference(), "QUEUED", notificationId, now);
            queued++;
        }

        if (queued == 0) {
            String reason = "No SMS delivery path can reach couriers for this brand (%s)"
                    .formatted(CourierBroadcastService.NO_COURIER_HAS_A_NUMBER);
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
            return new Outcome(store.find(tenantId, broadcast.id()).orElseThrow(), 0, noContact);
        }

        store.recordSent(tenantId, broadcast.id(), queued, now);
        audit.record(AuditFact.of("MARKETING_COURIER_BROADCAST_SENT", AuditClass.BUSINESS)
                .by(ActorRef.user(actorId.toString(), null))
                .at(ResourceScope.brand(tenantId, broadcast.brandId()))
                .target("CourierBroadcast", broadcast.id())
                .because("Sent a courier broadcast")
                // Staff 9.3a: "status" genuinely moves from DRAFT (guarded by the send) to SENT, the same
                // transition JdbcCourierBroadcastStore#recordSent performs.
                .changed(ChangeDocuments.diff(
                        Map.of("status", "DRAFT"),
                        Map.of("status", "SENT", "recipientCount", queued, "withoutNumber", noContact)))
                .correlatedBy(correlationId())
                .occurredAt(clock.instant())
                .build());
        return new Outcome(store.find(tenantId, broadcast.id()).orElseThrow(), queued, noContact);
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    /** The broadcast after the send, how many couriers were queued, and how many had no number. */
    public record Outcome(CourierBroadcastRow broadcast, int queued, int noContact) {}
}
