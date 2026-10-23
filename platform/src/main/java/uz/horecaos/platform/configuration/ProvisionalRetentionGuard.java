package uz.horecaos.platform.configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.ProvisionalRetention.Entry;

/**
 * Refuses a production start while a retention value is still provisional (ADR 0029).
 *
 * <p>Modelled on {@code TrackRetentionFloorCheck}, and for the same reason: unconfirmed numbers must not govern real customer
 * data, and a rule that is only written down is not a rule. {@link ProvisionalRetention} lists the values. An environment
 * confirms them by naming them in {@code horecaos.privacy.retention.confirmed} (the codes, or {@code all}) and by giving
 * the reference of the signed document in {@code horecaos.privacy.retention.confirmation-reference}; a confirmation without a
 * reference does not count, because "legal said yes" with nothing to point at is the thing this exists to stop.
 *
 * <p>Local, test and default profiles report and continue. Anything else refuses, unless {@code
 * horecaos.privacy.retention.check=REPORT_ONLY} keeps it in report mode for one deployment on purpose, which ADR 0029 asks
 * destructive retention to start in; that is logged as a warning on every start, because leaving it there is how an
 * unconfirmed number becomes permanent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProvisionalRetentionGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProvisionalRetentionGuard.class);

    private static final Set<String> REPORTING_PROFILES = Set.of("local", "test", "default");

    private final Environment environment;
    private final Set<String> confirmed;
    private final String reference;
    private final String mode;

    public ProvisionalRetentionGuard(
            Environment environment,
            @Value("${horecaos.privacy.retention.confirmed:}") String confirmed,
            @Value("${horecaos.privacy.retention.confirmation-reference:}") String reference,
            @Value("${horecaos.privacy.retention.check:AUTO}") String mode) {
        this.environment = environment;
        this.confirmed = Arrays.stream(confirmed.split(","))
                .map(String::strip)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.reference = reference.strip();
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    /** The values still provisional in this environment. Separate from {@link #run} so a test can call it without a context. */
    public List<Entry> unconfirmed() {
        if (reference.isEmpty()) {
            // A confirmation nobody can point at is not one.
            return ProvisionalRetention.entries();
        }
        if (confirmed.contains("all")) {
            return List.of();
        }
        return ProvisionalRetention.entries().stream()
                .filter(entry -> !confirmed.contains(entry.code()))
                .toList();
    }

    /** Whether the value behind {@code code} is still provisional here. */
    public boolean isProvisional(String code) {
        return unconfirmed().stream().anyMatch(entry -> entry.code().equals(code));
    }

    /** Logs the unconfirmed values and, where the profile or mode says so, refuses the start. */
    public List<Entry> check() {
        List<Entry> pending = unconfirmed();
        if (pending.isEmpty()) {
            return pending;
        }
        String report = pending.stream()
                .map(entry ->
                        "- %s: %s (%s, %s)".formatted(entry.code(), entry.description(), entry.value(), entry.source()))
                .collect(Collectors.joining(System.lineSeparator()));
        if (enforcing()) {
            throw new IllegalStateException("""
                    Retention values are still provisional (ADR 0029):

                    %s

                    Legal has not confirmed them. Record the confirmation in the deployment's environment:
                    horecaos.privacy.retention.confirmed=all (or the codes above) together with
                    horecaos.privacy.retention.confirmation-reference=<the signed document>, or set
                    horecaos.privacy.retention.check=REPORT_ONLY for one deployment while it is obtained.""".formatted(report));
        }
        log.warn("Retention values are provisional and unconfirmed (report-only):{}{}", System.lineSeparator(), report);
        return pending;
    }

    private boolean enforcing() {
        if ("ENFORCE".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("REPORT_ONLY".equalsIgnoreCase(mode)) {
            log.warn(
                    "horecaos.privacy.retention.check is REPORT_ONLY. Provisional retention values will not refuse this start.");
            return false;
        }
        List<String> active = List.of(environment.getActiveProfiles());
        return !(active.isEmpty() || active.stream().allMatch(REPORTING_PROFILES::contains));
    }
}
