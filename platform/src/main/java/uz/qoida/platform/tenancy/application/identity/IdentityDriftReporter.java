package uz.qoida.platform.tenancy.application.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.iam.api.organizations.OrganizationDirectory;
import uz.qoida.platform.iam.api.organizations.OrganizationProvisioner.OrganizationSnapshot;
import uz.qoida.platform.tenancy.application.port.TenantOrganizationLinkStore;
import uz.qoida.platform.tenancy.application.port.TenantOrganizationLinkStore.TenantOrganizationLink;

/**
 * Compares what Qoida believes about tenant identity against what Keycloak
 * actually holds, and reports the difference (ADR 0009).
 *
 * <p>It reports. It never corrects. ADR 0009 rejected automatic correction
 * outright, because this is destructive automation against identity data and a
 * false positive removes a real person's access — and the drift categories below
 * are exactly the ones where a wrong guess is expensive. Each finding is an
 * ADR 0027 audit fact and a metric; what to do about it is a human decision with
 * its own approval.
 *
 * <p>It runs on {@link OrganizationDirectory}, whose credential cannot write. A
 * report that could alter the memberships it exists to report on would be worth
 * less than no report.
 *
 * <p>In {@code tenancy} rather than {@code iam}, which is where ADR 0009's prose
 * puts it, because {@code audit} already depends on {@code iam} for
 * {@code ResourceScope} — so an {@code iam} class holding an
 * {@link AuditRecorder} closes a module cycle, and the boundary test says so. The
 * placement is not merely the legal one: the authority being reconciled is
 * {@code tenant.tenants.keycloak_organization_id}, a tenancy column that ADR 0009
 * deliberately declined to move. IAM keeps what is genuinely its own — the port
 * and the Keycloak adapter behind it.
 *
 * <p>Half of ADR 0009's comparison is here and half is blocked: the tenant's
 * organization is checked, and per-member drift is not, because
 * {@code iam.tenant_membership_links} has no migration yet. The linked subject
 * survives only in an onboarding step's result, which cannot be queried across
 * tenants. {@link DriftCode#MEMBERSHIP_UNVERIFIED} is not emitted for that
 * reason rather than because memberships are known to be correct.
 */
@Component
@ConditionalOnProperty(
        name = "qoida.iam.drift-report.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityDriftReporter {

    private static final Logger log = LoggerFactory.getLogger(IdentityDriftReporter.class);

    /** The job's identity in the audit trail; nothing here has a human actor. */
    private static final ActorRef REPORTER = ActorRef.systemJob("iam.identity-drift-report");

    /** What a drift report can conclude about one tenant. */
    public enum DriftCode {

        /** The stored organization id does not resolve. Never auto-repaired. */
        ORGANIZATION_MISSING,

        /** Resolves, but disabled while the tenant is not. Nobody can sign in. */
        ORGANIZATION_DISABLED,

        /** Resolves under an alias that is not the one the tenant derives. */
        ORGANIZATION_ALIAS_MISMATCH,

        /** An active tenant that was never linked at all. */
        ORGANIZATION_UNLINKED,

        /** Reserved: blocked on the ADR 0009 membership-link migration. */
        MEMBERSHIP_UNVERIFIED
    }

    /**
     * @param detail safe to log and to put in an audit fact: identifiers and
     *               states only, never a Keycloak message about a person
     */
    public record DriftFinding(UUID tenantId, DriftCode code, String detail) { }

    /**
     * @param unreachable tenants Keycloak could not be asked about, kept separate
     *                    from findings on purpose — "cannot ask" is not "wrong",
     *                    and reporting an unreachable realm as drift would raise
     *                    a finding for every tenant at once
     */
    public record DriftReport(List<DriftFinding> findings, int checked, int unreachable) { }

    private final TenantOrganizationLinkStore tenants;
    private final OrganizationDirectory directory;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int batchSize;

    private final AtomicInteger outstandingDrift = new AtomicInteger();
    private final AtomicReference<Instant> lastCompletedScan = new AtomicReference<>();

    public IdentityDriftReporter(
            TenantOrganizationLinkStore tenants,
            OrganizationDirectory directory,
            AuditRecorder audit,
            MeterRegistry meters,
            Clock clock,
            @Value("${qoida.iam.drift-report.batch-size:500}") int batchSize) {
        this.tenants = tenants;
        this.directory = directory;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
        this.batchSize = batchSize;

        meters.gauge("qoida.iam.identity.drift", this, reporter -> reporter.outstandingDrift.get());
        meters.gauge("qoida.iam.identity.drift.report.age.seconds", this,
                IdentityDriftReporter::secondsSinceLastCompletedScan);
    }

    @Scheduled(
            initialDelayString = "${qoida.iam.drift-report.initial-delay:PT2M}",
            fixedDelayString = "${qoida.iam.drift-report.interval:PT15M}")
    public void report() {
        try {
            DriftReport report = scan();
            if (!report.findings().isEmpty()) {
                log.warn("Identity drift report: {} findings across {} tenants",
                        report.findings().size(), report.checked());
            }
        } catch (RuntimeException failure) {
            // A scheduled report that throws would stop reporting silently. The
            // age gauge is what makes that visible, so it is deliberately not
            // updated here.
            log.error("Identity drift report failed", failure);
            scans("failed");
        }
    }

    /**
     * One pass over every tenant that should have an organization.
     *
     * <p>Public so an operator endpoint and a test can run it on demand rather
     * than waiting for the timer, and so what it found can be read rather than
     * inferred from a log line.
     */
    public DriftReport scan() {
        List<DriftFinding> findings = new ArrayList<>();
        int unreachable = 0;
        List<TenantOrganizationLink> links = tenants.tenantsToReconcile(batchSize);

        for (TenantOrganizationLink link : links) {
            try {
                inspect(link).ifPresent(findings::add);
            } catch (RuntimeException cannotAsk) {
                // Distinguished from a finding, because a Keycloak that is down
                // for a minute would otherwise report every tenant as missing an
                // organization and bury the one that really is.
                unreachable++;
                log.warn("Identity drift check for tenant {} could not reach Keycloak: {}",
                        link.tenantId(), cannotAsk.getClass().getSimpleName());
            }
        }

        findings.forEach(this::recordFinding);
        outstandingDrift.set(findings.size());
        lastCompletedScan.set(clock.instant());
        scans(unreachable > 0 ? "partial" : "complete");

        return new DriftReport(List.copyOf(findings), links.size(), unreachable);
    }

    private Optional<DriftFinding> inspect(TenantOrganizationLink link) {
        if (link.organizationId().isEmpty()) {
            // Only for a tenant that is meant to be usable. A PROVISIONING tenant
            // has not reached the organization step yet, and calling that drift
            // would make every new tenant a finding for as long as onboarding
            // takes.
            return "ACTIVE".equals(link.tenantStatus())
                    ? Optional.of(new DriftFinding(link.tenantId(), DriftCode.ORGANIZATION_UNLINKED,
                            "The tenant is active with no Keycloak organization"))
                    : Optional.empty();
        }

        String organizationId = link.organizationId().get();
        Optional<OrganizationSnapshot> organization = directory.getOrganization(organizationId);

        if (organization.isEmpty()) {
            return Optional.of(new DriftFinding(link.tenantId(), DriftCode.ORGANIZATION_MISSING,
                    "Organization %s is referenced by the tenant and does not exist in Keycloak"
                            .formatted(organizationId)));
        }
        OrganizationSnapshot found = organization.get();

        if (!found.enabled() && !"SUSPENDED".equals(link.tenantStatus())) {
            return Optional.of(new DriftFinding(link.tenantId(), DriftCode.ORGANIZATION_DISABLED,
                    "Organization %s is disabled while the tenant is %s"
                            .formatted(organizationId, link.tenantStatus())));
        }
        if (!link.expectedAlias().equals(found.alias())) {
            // Reported rather than corrected even though the alias is a mutable
            // field: an alias that changed under us may mean the id was reused,
            // and rewriting it would erase the only evidence of that.
            return Optional.of(new DriftFinding(link.tenantId(), DriftCode.ORGANIZATION_ALIAS_MISMATCH,
                    "Organization %s has alias %s where the tenant derives %s"
                            .formatted(organizationId, found.alias(), link.expectedAlias())));
        }
        return Optional.empty();
    }

    private void recordFinding(DriftFinding finding) {
        Counter.builder("qoida.iam.identity.drift.detected")
                .description("ADR 0009 identity drift between Qoida and Keycloak")
                .tag("code", finding.code().name())
                .register(meters)
                .increment();

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("driftCode", finding.code().name());
        changes.put("detail", finding.detail());

        audit.record(AuditFact.of("iam.identity_drift_detected", AuditClass.SECURITY)
                .by(REPORTER)
                .at(ResourceScope.tenant(finding.tenantId()))
                .target("Tenant", finding.tenantId())
                .because("Scheduled ADR 0009 identity drift report")
                .changed(changes)
                // REJECTED rather than FAILED: the report did not fail, it found
                // a state the platform refuses to accept as correct.
                .outcome(AuditFact.Outcome.REJECTED)
                .correlatedBy(finding.tenantId().toString())
                .occurredAt(clock.instant())
                .build());
    }

    private void scans(String outcome) {
        Counter.builder("qoida.iam.identity.drift.scans")
                .description("ADR 0009 scheduled identity drift report passes")
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    /**
     * Seconds since the last completed pass, and the reason this class publishes
     * a second gauge at all: a drift report that has stopped running reports no
     * drift, which is indistinguishable from a healthy estate until someone
     * notices the number has not moved in a week.
     */
    private double secondsSinceLastCompletedScan() {
        Instant last = lastCompletedScan.get();
        return last == null ? -1 : java.time.Duration.between(last, clock.instant()).toSeconds();
    }
}
