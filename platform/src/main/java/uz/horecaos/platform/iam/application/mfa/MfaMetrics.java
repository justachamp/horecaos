package uz.horecaos.platform.iam.application.mfa;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code horecaos.auth.staff.mfa{step,outcome}} (ADR 0148), with labels bounded to the two
 * closed sets below. A person, an account, a tenant or a code is never a label: the point of
 * the series is the alert, and a rise in {@code budget/exhausted} means somebody who holds a
 * password is guessing codes.
 */
@Component
public class MfaMetrics {

    static final String NAME = "horecaos.auth.staff.mfa";

    public enum Step {
        CHALLENGE,
        BUDGET,
        CONFIRM,
        RESET;

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Outcome {
        OK,
        INVALID,
        REQUIRED,
        EXHAUSTED;

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public MfaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(Step step, Outcome outcome) {
        registry.counter(NAME, "step", step.label(), "outcome", outcome.label()).increment();
    }
}
