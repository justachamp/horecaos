package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * The record that a courier was shown the telemetry transparency notice (ADR 0045, ADR 0179).
 *
 * <p>ADR 0045 makes the notice the precondition for switching position ingest on for a real tenant,
 * and a notice nobody can prove was shown is not one. The courier's app posts the version it showed
 * and this writes it down as an audit fact -- who (the courier's login subject), which courier row,
 * which version, when -- in the same append-only trail as every other security-relevant act. There
 * is no table of its own: an acknowledgement is an event, never a state to be updated, and a new
 * version of the notice is simply a new event.
 *
 * <p>The version is an identifier the tenant's notice carries ({@code 2026-10-draft-1}), validated
 * at the endpoint to a short token so the field cannot carry a name, a number or a sentence. Nothing
 * else is recorded: no position, no device, no network address.
 */
@Service
public class CourierNoticeService {

    private final AuditRecorder audit;
    private final Clock clock;

    public CourierNoticeService(AuditRecorder audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Acknowledgement acknowledge(UUID tenantId, UUID courierId, String noticeVersion, ActorRef actor) {
        Instant now = clock.instant();
        audit.record(AuditFact.of("courier.telemetry_notice.acknowledged", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because("Courier acknowledged the telemetry transparency notice")
                .usingCapability(Capability.COURIER_TELEMETRY.code())
                .changed(ChangeDocuments.created(Map.of("noticeVersion", noticeVersion)))
                .correlatedBy(courierId.toString())
                .occurredAt(now)
                .build());
        return new Acknowledgement(noticeVersion, now);
    }

    /** What was recorded: the version and the platform's clock, never the courier's. */
    public record Acknowledgement(String version, Instant acknowledgedAt) {}
}
