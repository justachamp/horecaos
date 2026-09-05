package uz.horecaos.platform.configuration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when {@link PlatformRole#runsWorkerWork()} — role {@code worker} or {@code both},
 * or {@link PlatformRole#PROPERTY} unset.
 *
 * <p>Backs {@link ConditionalOnWorkerRole}. A {@link SpringBootCondition} rather than a
 * bare {@code Condition} so a bean this disables explains itself in Spring Boot's
 * condition evaluation report the same way every {@code @ConditionalOnProperty} in this
 * codebase already does.
 */
final class OnWorkerRoleCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        @Nullable String configured = context.getEnvironment().getProperty(PlatformRole.PROPERTY);
        PlatformRole role = PlatformRole.fromProperty(configured);

        return role.runsWorkerWork()
                ? ConditionOutcome.match(PlatformRole.PROPERTY + " is " + roleText(configured)
                        + ", which runs the platform's " + "background work")
                : ConditionOutcome.noMatch(PlatformRole.PROPERTY + " is \"app\", which runs no background work");
    }

    private static String roleText(@Nullable String configured) {
        return configured == null || configured.isBlank() ? "unset (defaults to \"both\")" : "\"" + configured + "\"";
    }
}
