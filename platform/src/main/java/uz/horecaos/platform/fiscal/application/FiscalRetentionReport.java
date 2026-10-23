package uz.horecaos.platform.fiscal.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fiscal.api.FiscalEvidencePolicies;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalRetentionStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalRetentionStore.Holding;
import uz.horecaos.platform.tenancy.api.PolicyResolver;

/**
 * How much fiscal evidence each tenant holds and how much of it is older than its retention (ADR 0166 Decision 10,
 * ADR 0029).
 *
 * <p><strong>Report-only, by design.</strong> Nothing here deletes, marks or moves anything, and nothing could: no
 * database role the application uses may delete a fiscal row, so retention holds by absence, and the number this
 * reports is what an erasure would touch if one were ever built. Offboarding a tenant does not shorten it, because
 * the obligation is the tenant's (the key's own documentation says so, and a test pins it).
 *
 * <p>The period is {@link FiscalEvidencePolicies#effectiveRetention}: the tenant's figure, never below the
 * platform's, and flagged provisional while it is the default nobody has confirmed. A provisional figure is
 * reported as provisional, because a count of «documents past retention» computed from a guess is not a number to act
 * on without saying so.
 */
@Service
public class FiscalRetentionReport {

    private final JdbcFiscalRetentionStore store;
    private final PolicyResolver policies;
    private final Clock clock;

    public FiscalRetentionReport(JdbcFiscalRetentionStore store, PolicyResolver policies, Clock clock) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * One row per tenant that holds fiscal documents.
     *
     * @param retentionYears the period that applies to this tenant
     * @param provisional whether that figure is still the unconfirmed default
     * @param documents everything the tenant holds
     * @param pastRetention how many are dated before {@code retainedAfter}
     * @param retainedAfter the cutoff: now less the retention
     * @param oldest the date of the oldest document
     */
    public record TenantRetention(
            UUID tenantId,
            int retentionYears,
            boolean provisional,
            long documents,
            long pastRetention,
            Instant retainedAfter,
            @Nullable Instant oldest) {}

    public List<TenantRetention> report() {
        Instant now = clock.instant();
        List<TenantRetention> rows = new ArrayList<>();
        for (Holding holding : store.holdings()) {
            FiscalEvidencePolicies.FiscalEvidenceRetention retention =
                    FiscalEvidencePolicies.effectiveRetention(policies, holding.tenantId());
            Instant cutoff =
                    now.atZone(ZoneOffset.UTC).minusYears(retention.years()).toInstant();
            rows.add(new TenantRetention(
                    holding.tenantId(),
                    retention.years(),
                    retention.provisional(),
                    holding.documents(),
                    store.datedBefore(holding.tenantId(), cutoff),
                    cutoff,
                    holding.oldest()));
        }
        return List.copyOf(rows);
    }
}
