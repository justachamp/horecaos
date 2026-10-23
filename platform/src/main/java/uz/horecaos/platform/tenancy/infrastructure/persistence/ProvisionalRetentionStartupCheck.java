package uz.horecaos.platform.tenancy.infrastructure.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * ADR 0029's first guard on provisional retention (ADR 0183, gap-map row {@code 7.6c}): «a startup check refuses a production
 * profile while any retention value is still flagged provisional, so unconfirmed numbers cannot govern real customer data».
 *
 * <p><strong>What «provisional» means here.</strong> A key declared with {@code provisionalRetention} has a default that is a
 * guess. Confirming it is writing the value at {@code PLATFORM} scope, which ADR 0030 audits, so a key is provisional exactly
 * while it still resolves from its code default. There is no second switch to forget to flip.
 *
 * <p><strong>What it does, and the line it draws.</strong> On a local profile it does nothing. Everywhere else it reports every
 * provisional key that is still on its default, with who must confirm it, at {@code WARN} and as the gauge
 * {@code horecaos.configuration.provisional_retention_keys}, on every start, so that an unconfirmed number is never quiet.
 * It <em>refuses to start</em> only for a key marked as governing personal data: the customer funnel's 400 days keeps counts
 * and no person, and refusing a pilot's start over it, when counsel's answer is outside the platform, would trade a risk that
 * does not exist for an outage that does (ADR 0183, alternatives considered).
 *
 * <p>The report carries the key's code and its default, never a tenant or a person.
 */
@Component
public class ProvisionalRetentionStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProvisionalRetentionStartupCheck.class);

    /** The same set {@code SecretsProfileGuard} and the other startup guards treat as «not a deployment». */
    private static final Set<String> LOCAL_PROFILES = Set.of("local", "test", "default");

    private final ConfigurationResolver resolver;
    private final Environment environment;
    private final Collection<ConfigurationKey<?>> registry;
    private final AtomicInteger unconfirmed = new AtomicInteger();

    @Autowired
    public ProvisionalRetentionStartupCheck(
            ConfigurationResolver resolver, Environment environment, MeterRegistry meters) {
        this(resolver, environment, meters, ConfigurationKeys.all());
    }

    /** For a test that declares keys of its own. */
    ProvisionalRetentionStartupCheck(
            ConfigurationResolver resolver,
            Environment environment,
            MeterRegistry meters,
            Collection<ConfigurationKey<?>> registry) {
        this.resolver = resolver;
        this.environment = environment;
        this.registry = registry;
        Gauge.builder("horecaos.configuration.provisional_retention_keys", unconfirmed, AtomicInteger::doubleValue)
                .description("Retention keys still on a default nobody with the authority has confirmed (ADR 0029)")
                .register(meters);
    }

    /** One provisional key that still resolves from its code default. */
    public record Finding(
            String keyCode, Object defaultValue, boolean governsPersonalData, String awaitingConfirmationFrom) {}

    /** Every provisional key still on its default, in code order. Reads the platform scope: the retention keys are platform-only. */
    public List<Finding> findings() {
        return registry.stream()
                .filter(key -> key.provisional() != null)
                .filter(key -> resolver.resolve(key, ResourceScope.platform()).cameFromDefault())
                .map(ProvisionalRetentionStartupCheck::finding)
                .toList();
    }

    private static Finding finding(ConfigurationKey<?> key) {
        ConfigurationKey.ProvisionalRetention provisional = java.util.Objects.requireNonNull(key.provisional());
        return new Finding(
                key.code(),
                String.valueOf(key.defaultValue()),
                provisional.governsPersonalData(),
                provisional.awaitingConfirmationFrom());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isLocal()) {
            return;
        }
        List<Finding> found = findings();
        unconfirmed.set(found.size());
        for (Finding finding : found) {
            log.warn(
                    "Retention {} is still the provisional default {} (ADR 0029): {} must confirm it, by writing the value at"
                            + " platform scope. It {} personal data.",
                    finding.keyCode(),
                    finding.defaultValue(),
                    finding.awaitingConfirmationFrom(),
                    finding.governsPersonalData() ? "governs" : "does not govern");
        }
        List<String> refused = found.stream()
                .filter(Finding::governsPersonalData)
                .map(Finding::keyCode)
                .toList();
        if (!refused.isEmpty()) {
            throw new IllegalStateException("""
                    Profile %s would run with retention periods that govern personal data and that nobody has confirmed:
                    %s (ADR 0029, «Provisional retention»).
                    Confirm each with the person named in the log by writing the value at platform scope, or run a local
                    profile.""".formatted(List.of(environment.getActiveProfiles()), refused));
        }
    }

    private boolean isLocal() {
        List<String> active = List.of(environment.getActiveProfiles());
        return active.isEmpty() || active.stream().allMatch(LOCAL_PROFILES::contains);
    }
}
