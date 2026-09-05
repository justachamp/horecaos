package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How ADR 0023's fourth switch is reconciled: {@link ConditionalOnWorkerRole} stacked
 * on a bean's own {@code @ConditionalOnProperty}, exactly the shape now carried by
 * {@code TenancyEventListener} and {@code FulfillmentCommandListener}.
 *
 * <p>A synthetic bean rather than the real listeners, because a
 * {@code @KafkaListener} needs a broker and this composition is a fact about Spring's
 * condition evaluation — every {@code @Conditional} present on one element is ANDed —
 * that owes nothing to Kafka. {@code uz.horecaos.platform.integration.inbox
 * .WorkerRoleAnnotationCoverageTests} is what pins the real classes to this shape.
 */
class ConditionalOnWorkerRoleCompositionTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SwitchedWorkerBean.class);

    @Test
    @DisplayName("both the switch and a worker-capable role are required")
    void bothConditionsMustPass() {
        // Neither set: the property's own matchIfMissing keeps it on, so only the
        // role decides, and the default role runs worker work.
        runner.run(context -> assertThat(context).hasSingleBean(Marker.class));

        // The operational switch off: absent regardless of role.
        runner.withPropertyValues("test.inbox-like.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(Marker.class));

        // The switch on, role app: still absent, which is the gap ADR 0023 named —
        // this switch alone never stopped consumption under a role of "app".
        runner.withPropertyValues("test.inbox-like.enabled=true", "horecaos.runtime.role=app")
                .run(context -> assertThat(context)
                        .as("the switch alone cannot express \"not on this role\"; "
                                + "ConditionalOnWorkerRole is what closes that")
                        .doesNotHaveBean(Marker.class));

        // The switch on, role worker: present.
        runner.withPropertyValues("test.inbox-like.enabled=true", "horecaos.runtime.role=worker")
                .run(context -> assertThat(context).hasSingleBean(Marker.class));

        // The switch off, role worker: the switch still wins on its own.
        runner.withPropertyValues("test.inbox-like.enabled=false", "horecaos.runtime.role=worker")
                .run(context -> assertThat(context).doesNotHaveBean(Marker.class));
    }

    /** Present in the context exactly when {@link SwitchedWorkerBean} was registered. */
    static final class Marker {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.inbox-like.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnWorkerRole
    static class SwitchedWorkerBean {

        @Bean
        Marker marker() {
            return new Marker();
        }
    }
}
