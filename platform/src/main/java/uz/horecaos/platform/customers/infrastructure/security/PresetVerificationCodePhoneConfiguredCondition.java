package uz.horecaos.platform.customers.infrastructure.security;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@link PresetVerificationCodeSource#PHONE_PROPERTY} resolves to
 * a non-blank value (ADR 0051).
 *
 * <p>Plain {@code @ConditionalOnProperty(name = PHONE_PROPERTY)}, with no
 * {@code havingValue}, is not enough here: Spring Boot's own {@code OnPropertyCondition}
 * treats any <em>present</em> key as a match, including one that resolves to the
 * empty string. That is exactly what an unset pre-production variable produces —
 * {@code deploy/compose.production.yml} passes
 * {@code HORECAOS_CUSTOMERS_VERIFICATION_PRESET_PHONE: ${HORECAOS_VERIFICATION_PRESET_PHONE:-}}
 * into the container, so an operator who has not filled in
 * {@code HORECAOS_VERIFICATION_PRESET_PHONE} still gets the property set to
 * {@code ""} on the process environment, not left unset. Without this condition
 * that would still satisfy {@code @ConditionalOnProperty} and hand the bean's
 * constructor an empty string, which fails {@code PhoneNumber.requireDeliverableMobile}
 * and takes the whole application down over a variable nobody meant to configure.
 * {@code havingValue} cannot fix this either: the preset phone number legitimately
 * varies, so there is no single required value to compare against.
 *
 * <p>A {@link SpringBootCondition} rather than a bare {@code Condition}, the same
 * choice {@code OnWorkerRoleCondition} makes, so a skipped bean explains itself in
 * Spring Boot's own condition evaluation report.
 */
final class PresetVerificationCodePhoneConfiguredCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        @Nullable String value = context.getEnvironment().getProperty(PresetVerificationCodeSource.PHONE_PROPERTY);

        return value != null && !value.isBlank()
                ? ConditionOutcome.match(PresetVerificationCodeSource.PHONE_PROPERTY + " is configured")
                : ConditionOutcome.noMatch(PresetVerificationCodeSource.PHONE_PROPERTY + " is unset or blank");
    }
}
