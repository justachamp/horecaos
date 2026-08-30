package uz.qoida.platform.fulfillment.application;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import uz.qoida.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The scheduler's own arithmetic (ADR 0014).
 *
 * <p>The first of these is here for a specific reason. A lease shorter than the
 * batch it covers expires while the batch is still running, so a second worker
 * re-runs a tick whose partner call is in flight — the idempotency key saves the
 * order, but nobody is told it happened. The constructor refuses that pair, which
 * means the refusal is reachable by shipping defaults that disagree with each
 * other, and a bean that will not construct fails every {@code @SpringBootTest} on
 * the platform with a message about this class. Checking the shipped values here
 * turns that into one failing assertion naming the two numbers.
 */
class DeliverySourcingSchedulerTests {

    @Test
    @DisplayName("the shipped defaults give the lease more time than a whole batch can take")
    void theShippedDefaultsAreConsistent() {
        List<String> defaults = valueDefaults(DeliverySourcingScheduler.class);
        int batchSize = Integer.parseInt(defaults.get(0));
        Duration lease = Duration.parse("PT" + defaults.get(1).toUpperCase(java.util.Locale.ROOT));
        Duration tick = Duration.parse("PT" + defaults.get(2).toUpperCase(java.util.Locale.ROOT));

        assertThat(lease)
                .as("a batch of %d ticks at %s each outlasts a %s lease, so a live worker would "
                        + "lose its own claim mid-partner-call", batchSize, tick, lease)
                .isGreaterThanOrEqualTo(tick.multipliedBy(batchSize).plusSeconds(5));
    }

    @Test
    @DisplayName("a lease that cannot outlast its batch is refused rather than deployed")
    void anInconsistentLeaseIsRefused() {
        Throwable refusal = catchThrowable(() -> new DeliverySourcingScheduler(
                new JdbcSourcingJobStore(null), null, Clock.systemUTC(), new SimpleMeterRegistry(),
                20, Duration.ofSeconds(30), Duration.ofSeconds(30), "qoida-platform"));

        assertThat(refusal).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
    }

    /** The {@code ${key:default}} halves of every {@code @Value} on the constructor. */
    private static List<String> valueDefaults(Class<?> type) {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        List<String> defaults = new ArrayList<>();
        for (Annotation[] onParameter : constructor.getParameterAnnotations()) {
            for (Annotation annotation : onParameter) {
                if (annotation instanceof Value value) {
                    String expression = value.value();
                    defaults.add(expression.substring(expression.indexOf(':') + 1,
                            expression.length() - 1));
                }
            }
        }
        assertThat(defaults)
                .as("the scan found no @Value defaults, so it is measuring nothing")
                .isNotEmpty();
        return defaults;
    }
}
