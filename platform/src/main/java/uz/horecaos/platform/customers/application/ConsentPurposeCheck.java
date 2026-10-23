package uz.horecaos.platform.customers.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.application.ConsentService.Source;

/**
 * Notices a consent decision recorded under a purpose the tenant has not registered (ADR 0109, ADR 0029).
 *
 * <p>{@code customer.consent_decisions.purpose} has been free text since V0017, and ADR 0109 built the registry of purposes
 * without making {@link ConsentService#record} refuse a purpose outside it. That refusal needs two answers the platform does not
 * have: legal's lawful basis per purpose (ADR 0029's open input), and which of the existing writers' spellings (the SendPulse
 * import writes {@code MARKETING}) should be migrated rather than refused. Refusing first and asking afterwards would break
 * an import or a bot on the day it is switched on.
 *
 * <p>So this only <em>counts</em>, and only when asked to. With {@code horecaos.customers.consent.purpose-check=REPORT_ONLY} every
 * decision under an unregistered purpose increments {@code horecaos.customers.consent.unregistered_purpose}, tagged with who wrote it
 * (the {@link Source}, a closed set), and a line is logged with the source and the tenant, never the purpose: the purpose is text a
 * person or an importer typed, and ADR 0029 keeps free text out of logs. The default is {@code OFF}, which costs nothing and
 * changes nothing. Which purposes are unregistered is a query the runbook gives, not something logged.
 *
 * <p>It never refuses. Switching it to refuse is a decision for when legal has answered, and a different class.
 */
@Component
public class ConsentPurposeCheck {

    private static final Logger log = LoggerFactory.getLogger(ConsentPurposeCheck.class);

    /** What the check does with a decision under an unregistered purpose. */
    public enum Mode {
        /** Nothing: no lookup, no count. */
        OFF,
        /** Count and log it; record the decision exactly as before. */
        REPORT_ONLY
    }

    private final @Nullable ConsentTypeService types;
    private final Mode mode;
    private final @Nullable MeterRegistry meters;
    private final AtomicLong observed = new AtomicLong();

    @Autowired
    public ConsentPurposeCheck(
            ConsentTypeService types,
            @Value("${horecaos.customers.consent.purpose-check:OFF}") String mode,
            @Nullable MeterRegistry meters) {
        this(types, parse(mode), meters);
    }

    public ConsentPurposeCheck(@Nullable ConsentTypeService types, Mode mode, @Nullable MeterRegistry meters) {
        this.types = types;
        this.mode = mode;
        this.meters = meters;
    }

    /** A check that does nothing, for a caller built without the registry (a unit test, a tool). */
    public static ConsentPurposeCheck off() {
        return new ConsentPurposeCheck(null, Mode.OFF, null);
    }

    private static Mode parse(String value) {
        try {
            return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // An unrecognised value must not turn a check on, nor stop the platform starting: it is the quiet mode.
            log.warn("horecaos.customers.consent.purpose-check has an unrecognised value; the check stays OFF");
            return Mode.OFF;
        }
    }

    /** Looks at one decision about to be recorded. Never throws and never changes it. */
    public void observe(UUID tenantId, String purpose, Source source) {
        ConsentTypeService registry = types;
        if (mode == Mode.OFF || registry == null) {
            return;
        }
        try {
            if (registry.isRegistered(tenantId, purpose)) {
                return;
            }
            observed.incrementAndGet();
            if (meters != null) {
                Counter.builder("horecaos.customers.consent.unregistered_purpose")
                        .description("Consent decisions recorded under a purpose the tenant has not registered")
                        .tag("source", source.name())
                        .register(meters)
                        .increment();
            }
            log.warn(
                    "A consent decision was recorded under a purpose not in the tenant's registry (report-only): "
                            + "tenant={}, source={}",
                    tenantId,
                    source);
        } catch (RuntimeException lookupFailed) {
            // A check that can break the write it is watching is worse than no check.
            log.warn(
                    "The consent purpose check could not run: {}",
                    lookupFailed.getClass().getSimpleName());
        }
    }

    /** How many decisions this instance has seen under an unregistered purpose, for a test. */
    public long observedCount() {
        return observed.get();
    }

    public Mode mode() {
        return mode;
    }
}
