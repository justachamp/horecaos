package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.CatalogConfigurationKeys;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Whether the national ИКПУ/MXIK list is loaded, which dataset it is and how old (ADR 0173 Decision 9).
 *
 * <p>Its own small service so the platform read, the tenant's twin of it and the daily freshness check all
 * answer the same way, and so none of them needs the import machinery to say "not loaded". The tenant console
 * shows this where it used to say "nothing found": a picker that finds nothing in a list nobody loaded is a
 * different problem, with a different owner, than a picker that finds nothing in a list that is there.
 */
@Service
public class MxikReferenceStatusService {

    private final JdbcMxikImportStore store;
    private final ConfigurationResolver configuration;
    private final Clock clock;

    public MxikReferenceStatusService(JdbcMxikImportStore store, ConfigurationResolver configuration, Clock clock) {
        this.store = store;
        this.configuration = configuration;
        this.clock = clock;
    }

    /**
     * @param loaded         the state row exists, or the list holds any row because a load predates the state
     * @param datasetVersion the dataset version the last applied run wrote
     * @param validRows      codes valid today
     * @param withdrawnRows  codes whose withdrawal date has been reached
     * @param ageDays        whole days since the last apply
     * @param maxAgeDays     {@code catalog.mxik_reference_max_age_days} in force
     * @param stale          {@code ageDays} is past {@code maxAgeDays}
     */
    public record Status(
            boolean loaded,
            @Nullable Integer datasetVersion,
            @Nullable Instant importedAt,
            @Nullable LocalDate sourcePublishedOn,
            int validRows,
            int withdrawnRows,
            @Nullable Long ageDays,
            @Nullable Integer maxAgeDays,
            boolean stale) {}

    @Transactional(readOnly = true)
    public Status status() {
        Integer maxAge =
                configuration.value(CatalogConfigurationKeys.MXIK_REFERENCE_MAX_AGE_DAYS, ResourceScope.platform());
        return store.state()
                .map(state -> {
                    long age = Duration.between(state.importedAt(), clock.instant())
                            .toDays();
                    return new Status(
                            true,
                            state.datasetVersion(),
                            state.importedAt(),
                            state.sourcePublishedOn(),
                            store.validRows(),
                            store.withdrawnRows(),
                            age,
                            maxAge,
                            maxAge != null && age > maxAge);
                })
                .orElseGet(() -> new Status(
                        store.validRows() + store.withdrawnRows() > 0,
                        null,
                        null,
                        null,
                        store.validRows(),
                        store.withdrawnRows(),
                        null,
                        maxAge,
                        false));
    }
}
