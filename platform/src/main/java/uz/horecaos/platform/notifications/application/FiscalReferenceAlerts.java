package uz.horecaos.platform.notifications.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.MxikReferenceApplied;
import uz.horecaos.platform.catalog.api.MxikReferenceHealth;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;

/**
 * The control-plane incidents about the national ИКПУ/MXIK list (ADR 0173 Decision 9, ADR 0085).
 *
 * <p>An absent list silences the validator's unknown-code finding by design, so without a check the platform
 * would go on judging every classification correct against a list nobody loaded. {@link #check()} raises
 * {@code FISCAL_REFERENCE_NOT_LOADED} when the list is empty while any tenant holds a classified node, and
 * {@code FISCAL_REFERENCE_STALE} when it is older than {@code catalog.mxik_reference_max_age_days}. One incident
 * per class and subject; a second sweep counts an occurrence. An applied import resolves both, in the
 * transaction that applied it.
 *
 * <p>Here rather than in the catalog because the catalog may not depend on this module (the inventory digest in
 * here already reads the catalog's item names, and a dependency the other way is a cycle). The catalog states
 * the facts through {@link MxikReferenceHealth} and announces a load with {@link MxikReferenceApplied}.
 */
@Component
public class FiscalReferenceAlerts {

    public static final String NOT_LOADED = "FISCAL_REFERENCE_NOT_LOADED";
    public static final String STALE = "FISCAL_REFERENCE_STALE";
    public static final String SUBJECT_TYPE = "FiscalReference";
    public static final String SUBJECT_ID = "mxik";

    private final MxikReferenceHealth reference;
    private final ControlPlaneAlertPort alerts;
    private final Clock clock;
    private final AtomicLong ageDays = new AtomicLong(-1);
    private final AtomicLong validRows = new AtomicLong();
    private final AtomicLong withdrawnRows = new AtomicLong();

    public FiscalReferenceAlerts(
            MxikReferenceHealth reference, ControlPlaneAlertPort alerts, Clock clock, MeterRegistry metrics) {
        this.reference = reference;
        this.alerts = alerts;
        this.clock = clock;
        // No code and no label in a metric: only how big the list is and how old (ADR 0173, observability).
        Gauge.builder("horecaos.fiscal_reference.age_days", ageDays, AtomicLong::get)
                .register(metrics);
        Gauge.builder("horecaos.fiscal_reference.rows", validRows, AtomicLong::get)
                .tag("state", "valid")
                .register(metrics);
        Gauge.builder("horecaos.fiscal_reference.rows", withdrawnRows, AtomicLong::get)
                .tag("state", "withdrawn")
                .register(metrics);
    }

    /**
     * Looks at the list once and raises what is due.
     *
     * @return the event class raised, if any
     */
    public Optional<String> check() {
        MxikReferenceHealth.Health health = reference.health();
        ageDays.set(health.ageDays() == null ? -1 : health.ageDays());
        validRows.set(health.validRows());
        withdrawnRows.set(health.withdrawnRows());
        if (!health.loaded()) {
            if (health.anyTenantClassified()) {
                raise(NOT_LOADED, Map.of("classifiedNodesExist", "true"));
                return Optional.of(NOT_LOADED);
            }
            return Optional.empty();
        }
        if (health.stale() && health.ageDays() != null && health.maxAgeDays() != null) {
            raise(
                    STALE,
                    Map.of(
                            "ageDays", String.valueOf(health.ageDays()),
                            "maxAgeDays", String.valueOf(health.maxAgeDays())));
            return Optional.of(STALE);
        }
        return Optional.empty();
    }

    /** A successful import is what ends both incidents; heard inside the apply's own transaction. */
    @EventListener
    public void onApplied(MxikReferenceApplied applied) {
        alerts.resolve(
                NOT_LOADED, SUBJECT_TYPE, SUBJECT_ID, applied.actorSubject(), "A successful import loaded the list");
        alerts.resolve(
                STALE, SUBJECT_TYPE, SUBJECT_ID, applied.actorSubject(), "A successful import refreshed the list");
    }

    private void raise(String eventClass, Map<String, String> variables) {
        alerts.raise(new ControlPlaneAlert(eventClass, SUBJECT_TYPE, SUBJECT_ID, variables, clock.instant()));
    }
}
