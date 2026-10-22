package uz.horecaos.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fiscal.api.FiscalEvidencePolicies;
import uz.horecaos.platform.fiscal.api.FiscalEvidencePolicies.FiscalEvidenceRetention;
import uz.horecaos.platform.fiscal.application.FiscalRetentionReport;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalRetentionStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalRetentionStore.Holding;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * The retention of fiscal evidence has readers beyond the evidence screen (ADR 0166 Decision 10).
 *
 * <p>The key is settable at the platform and the tenant, and the record says a tenant may lengthen the period and
 * no deployment may shorten it below what the platform states. The resolver alone returns the most specific
 * scope, which would let a tenant write five years under a platform that states seven, so the rule lives in one
 * place every reader goes through, and these cases pin it. The report is the first reader that does not draw a
 * screen: a count of what is past retention, which deletes nothing.
 */
class FiscalRetentionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

    /** A resolver holding a document at some scopes. */
    private static PolicyResolver resolverWith(Map<ScopeType, FiscalEvidenceRetention> documents) {
        return new PolicyResolver() {
            @Override
            @SuppressWarnings("unchecked")
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                // Most specific wins, like the real resolver: the tenant's document when there is one, else the
                // platform's.
                for (ScopeType level : List.of(ScopeType.TENANT, ScopeType.PLATFORM)) {
                    if (documents.containsKey(level) && scope.chain().stream().anyMatch(s -> s.type() == level)) {
                        return Optional.of((ResolvedPolicy<P>) new ResolvedPolicy<>(
                                key.code(), UUID.randomUUID(), 1, level, "hash", documents.get(level)));
                    }
                }
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> resolveUncached(PolicyKey<P> key, ResourceScope scope) {
                return resolve(key, scope);
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                return Optional.empty();
            }
        };
    }

    private static Map<ScopeType, FiscalEvidenceRetention> documents(
            @Nullable FiscalEvidenceRetention platform, @Nullable FiscalEvidenceRetention tenant) {
        Map<ScopeType, FiscalEvidenceRetention> documents = new EnumMap<>(ScopeType.class);
        if (platform != null) {
            documents.put(ScopeType.PLATFORM, platform);
        }
        if (tenant != null) {
            documents.put(ScopeType.TENANT, tenant);
        }
        return documents;
    }

    @Test
    @DisplayName("nothing configured is seven years and provisional, the record's default")
    void nothingConfiguredIsTheDefault() {
        FiscalEvidenceRetention retention = FiscalEvidencePolicies.effectiveRetention(resolverWith(Map.of()), TENANT);

        assertThat(retention.years()).isEqualTo(7);
        assertThat(retention.provisional()).isTrue();
    }

    @Test
    @DisplayName("a tenant may lengthen the period")
    void aTenantMayLengthenIt() {
        FiscalEvidenceRetention retention = FiscalEvidencePolicies.effectiveRetention(
                resolverWith(documents(new FiscalEvidenceRetention(7, true), new FiscalEvidenceRetention(10, false))),
                TENANT);

        assertThat(retention.years()).isEqualTo(10);
        assertThat(retention.provisional()).isFalse();
    }

    @Test
    @DisplayName("a tenant cannot shorten the period below what the platform states, however it is written")
    void aTenantCannotShortenIt() {
        FiscalEvidenceRetention retention = FiscalEvidencePolicies.effectiveRetention(
                resolverWith(documents(new FiscalEvidenceRetention(7, false), new FiscalEvidenceRetention(5, false))),
                TENANT);

        assertThat(retention.years())
                .as("the obligation is the tenant's, but the platform answers for having kept what it was handed")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("on a tie the tenant's own statement stands, because it may be the one somebody confirmed")
    void aTieGoesToTheTenant() {
        FiscalEvidenceRetention retention = FiscalEvidencePolicies.effectiveRetention(
                resolverWith(documents(new FiscalEvidenceRetention(7, true), new FiscalEvidenceRetention(7, false))),
                TENANT);

        assertThat(retention.provisional()).isFalse();
    }

    @Test
    @DisplayName("a policy that cannot be read is the platform default, never a shorter period")
    void anUnreadablePolicyIsTheDefault() {
        PolicyResolver broken = new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                throw new IllegalStateException("document unreadable");
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> resolveUncached(PolicyKey<P> key, ResourceScope scope) {
                throw new IllegalStateException("document unreadable");
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                return Optional.empty();
            }
        };

        assertThat(FiscalEvidencePolicies.effectiveRetention(broken, TENANT).years())
                .isEqualTo(7);
    }

    @Test
    @DisplayName(
            "the report counts what is dated before now less the tenant's retention, says when the figure is provisional, and asks the store for nothing else")
    void theReportCountsWhatIsPastRetention() {
        JdbcFiscalRetentionStore store = mock(JdbcFiscalRetentionStore.class);
        Instant oldest = Instant.parse("2017-01-05T00:00:00Z");
        when(store.holdings()).thenReturn(List.of(new Holding(TENANT, 12, oldest)));
        Instant cutoff = Instant.parse("2019-10-23T09:00:00Z");
        when(store.datedBefore(TENANT, cutoff)).thenReturn(3L);
        FiscalRetentionReport report = new FiscalRetentionReport(
                store,
                resolverWith(documents(new FiscalEvidenceRetention(7, true), null)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        List<FiscalRetentionReport.TenantRetention> rows = report.report();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.tenantId()).isEqualTo(TENANT);
            assertThat(row.retentionYears()).isEqualTo(7);
            assertThat(row.provisional())
                    .as("a count of documents past retention, computed from a guess, says so")
                    .isTrue();
            assertThat(row.documents()).isEqualTo(12);
            assertThat(row.pastRetention()).isEqualTo(3);
            assertThat(row.retainedAfter()).isEqualTo(cutoff);
            assertThat(row.oldest()).isEqualTo(oldest);
        });
        verify(store).holdings();
        verify(store).datedBefore(TENANT, cutoff);
    }
}
