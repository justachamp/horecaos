package uz.qoida.platform.reporting.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.reporting.domain.MetricDefinition;
import uz.qoida.platform.reporting.domain.MetricRegistry;
import uz.qoida.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * Records finance's signature over a metric definition (ADR 0043).
 *
 * <p>The signature is the thing that moves a number from provisional to settled
 * on every screen at once, so it is an audited decision rather than a
 * configuration write. The audit fact is written in the same transaction as the
 * signature under ADR 0027: a definition that became authoritative with no record
 * of who agreed to it is exactly the state the registry exists to avoid.
 *
 * <p>Signing is not idempotent by design. A second signature over the same words
 * says nothing new, and replacing the first would lose who actually decided, so a
 * signed metric is refused rather than re-signed.
 */
@Service
public class MetricSigningService {

    private final JdbcReportingStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public MetricSigningService(JdbcReportingStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public MetricDefinition sign(String metricCode, ActorRef actor, String reason) {
        MetricDefinition definition = MetricRegistry.require(metricCode);

        int updated = store.sign(definition.id().name(), definition.id().version(),
                actor.subject(), clock.instant());
        if (updated == 0) {
            throw new AlreadySignedException(metricCode);
        }

        audit.record(AuditFact.of("reporting.metric.signed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                // A metric id is a name and not a UUID, so the definition's digest
                // is what identifies the exact wording that was signed. Recording
                // it is what lets an auditor prove the signature covers the text
                // in the release rather than whatever the registry says today.
                .target("MetricDefinition", UUID.nameUUIDFromBytes(
                        definition.id().code().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because(reason)
                .changed(Map.of(
                        "metricCode", definition.id().code(),
                        "definitionDigest", definition.digest(),
                        "definition", definition.definition()))
                .evidence(definition.digest())
                .usingCapability("metric.manage")
                .correlatedBy(UUID.randomUUID().toString())
                .occurredAt(clock.instant())
                .build());

        return definition;
    }

    /** The metric already carries a signature, and a signature is not replaced. */
    public static final class AlreadySignedException extends IllegalStateException {

        public AlreadySignedException(String metricCode) {
            super(("%s is already signed. A definition change is a new version (ADR 0043), so "
                    + "sign that rather than re-signing this one.").formatted(metricCode));
        }
    }
}
