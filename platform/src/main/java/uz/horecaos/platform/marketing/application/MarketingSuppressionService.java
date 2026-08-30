package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.SuppressionReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;

/**
 * Recording and lifting suppressions (ADR 0044).
 *
 * <p>A suppression list is a new way to wrongly silence a real customer, so both
 * halves of it are audited and the lift is the more dangerous one: a marketer who
 * could clear their own bounce list could inflate reach, and the removal would
 * look like the customer never bounced. Nothing here deletes a row.
 *
 * <p>{@link SuppressionReason#PLATFORM_BLOCK} is refused to a tenant actor. It is
 * how HorecaOS stops a tenant messaging someone who complained to a regulator, and a
 * tenant operator who could set it could also lift it, which would make it worth
 * nothing. The database says the same thing in a CHECK; this refusal exists so the
 * caller gets an explanation rather than a constraint violation.
 */
@Service
public class MarketingSuppressionService {

    /** How the customer's own unsubscribe link identifies itself. */
    public static final String ACTOR_CUSTOMER = "CUSTOMER";
    public static final String ACTOR_OPERATOR = "OPERATOR";
    public static final String ACTOR_PROVIDER = "PROVIDER";
    public static final String ACTOR_CONTROL_PLANE = "CONTROL_PLANE";

    private final JdbcEngagementStore engagement;
    private final AuditRecorder audit;
    private final Clock clock;

    public MarketingSuppressionService(JdbcEngagementStore engagement, AuditRecorder audit,
            Clock clock) {
        this.engagement = engagement;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records a suppression.
     *
     * @param brandId null to suppress across every brand of the tenant. A customer
     *                who complained to a regulator did not complain about one
     *                brand's newsletter
     * @param channel null to suppress every channel. A bounced number is one
     *                transport; a complaint is all of them
     */
    @Transactional
    public UUID suppress(UUID tenantId, UUID brandId, UUID accountId, MarketingChannel channel,
            SuppressionReason reason, String actorType, UUID actorId, ActorRef actor,
            String statedReason, String correlationId) {

        if (reason.isControlPlaneOnly() && !ACTOR_CONTROL_PLANE.equals(actorType)) {
            throw new IllegalArgumentException(
                    "%s is settable only by the control plane".formatted(reason));
        }

        Instant now = clock.instant();
        Instant expiresAt = reason.lifetime().map(now::plus).orElse(null);

        UUID id = engagement.recordSuppression(tenantId, brandId, accountId,
                channel == null ? null : channel.name(), reason.name(),
                ACTOR_OPERATOR.equals(actorType) ? actorId : null,
                actorType, statedReason, now, expiresAt);

        // The change document names the reason, the scope, and whether it expires,
        // and deliberately not the customer's contact value. The account id is a
        // pseudonymous reference; the number it resolves to is the customers
        // module's to reveal, under its own capability and its own audit record.
        Map<String, Object> changed = new HashMap<>();
        changed.put("customerAccountId", accountId);
        changed.put("reason", reason.name());
        changed.put("brandScope", brandId == null ? "TENANT_WIDE" : brandId.toString());
        changed.put("channelScope", channel == null ? "ALL_CHANNELS" : channel.name());
        changed.put("expiresAt", expiresAt == null ? "NEVER" : expiresAt.toString());

        audit.record(AuditFact.of("MARKETING_SUPPRESSION_RECORDED", AuditClass.SECURITY)
                .by(actor)
                .at(brandId == null
                        ? ResourceScope.tenant(tenantId) : ResourceScope.brand(tenantId, brandId))
                .target("MarketingSuppression", id)
                .because(statedReason == null ? "Suppression recorded by " + actorType
                        : statedReason)
                .changed(changed)
                .usingCapability("suppression.manage")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return id;
    }

    /**
     * The customer's own unsubscribe.
     *
     * <p>Tenant-wide and channel-neutral by construction. A customer who taps
     * "stop" has not asked to stop receiving SMS while continuing to receive push,
     * and offering that distinction through this path would turn a refusal into a
     * preference.
     */
    @Transactional
    public UUID unsubscribe(UUID tenantId, UUID brandId, UUID accountId, ActorRef actor,
            String correlationId) {
        return suppress(tenantId, brandId, accountId, null, SuppressionReason.UNSUBSCRIBE,
                ACTOR_CUSTOMER, null, actor, "The customer unsubscribed", correlationId);
    }

    /**
     * Lifts a suppression, leaving the row and naming who lifted it.
     *
     * @return true when a suppression was open and is now closed
     */
    @Transactional
    public boolean lift(UUID tenantId, UUID suppressionId, UUID liftedBy, ActorRef actor,
            String reason, String correlationId) {

        var suppression = engagement.findSuppression(tenantId, suppressionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No suppression %s belongs to this tenant".formatted(suppressionId)));

        Instant now = clock.instant();
        boolean lifted = engagement.liftSuppression(tenantId, suppressionId, liftedBy, reason, now);

        Map<String, Object> changed = new HashMap<>();
        changed.put("customerAccountId", suppression.customerAccountId());
        changed.put("reason", suppression.reason());
        changed.put("lifted", lifted);

        audit.record(AuditFact.of("MARKETING_SUPPRESSION_LIFTED", AuditClass.SECURITY)
                .by(actor)
                .at(suppression.brandId() == null
                        ? ResourceScope.tenant(tenantId)
                        : ResourceScope.brand(tenantId, suppression.brandId()))
                .target("MarketingSuppression", suppressionId)
                .outcome(lifted ? AuditFact.Outcome.SUCCEEDED : AuditFact.Outcome.REJECTED)
                .because(reason)
                .changed(changed)
                .usingCapability("suppression.manage")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return lifted;
    }
}
