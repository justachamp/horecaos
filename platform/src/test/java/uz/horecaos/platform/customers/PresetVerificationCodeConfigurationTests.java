package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uz.horecaos.platform.customers.application.RandomVerificationCodeSource;
import uz.horecaos.platform.customers.application.VerificationCodeSource;
import uz.horecaos.platform.customers.domain.VerificationCode;
import uz.horecaos.platform.customers.infrastructure.security.PresetVerificationCodeSource;

/**
 * Whether {@link PresetVerificationCodeSource}'s three locks actually boot the way
 * {@code deploy/compose.production.yml} relies on them booting (ADR 0051).
 *
 * <p>{@code PresetVerificationCodeTests} proves the same logic with direct
 * constructor calls and a {@code MockEnvironment}, which is fast but never
 * exercises Spring's own {@code @Profile}/{@code @Conditional} evaluation or
 * component wiring — precisely the layer a Spring-profile typo or an env var
 * that resolves to the empty string lives in. This class registers both
 * {@link VerificationCodeSource} implementations the same way component scanning
 * would and lets Spring decide which one exists, against a real {@code
 * ConfigDataApplicationContextInitializer} pass over {@code application.yml} —
 * the same combination {@code HandoverPepperReferenceDefaultTests} uses for the
 * same reason.
 */
class PresetVerificationCodeConfigurationTests {

    private static final String PHONE_PROPERTY = PresetVerificationCodeSource.PHONE_PROPERTY;
    private static final String CODE_PROPERTY = PresetVerificationCodeSource.CODE_PROPERTY;

    /**
     * Same spelling every {@code @Value("${horecaos.environment:local}")} injection
     * point in the codebase uses (KeycloakConfiguration, SecretsConfiguration,
     * DataEncryptionKeyProvider, ...). {@link PresetVerificationCodeSource} does not
     * own a copy of this constant, because it deliberately does not consult the
     * property — this class still needs the literal to prove the bean builds under
     * pre-production's real value for it.
     */
    private static final String ENVIRONMENT_PROPERTY = "horecaos.environment";

    private static final String PRESET = "+998000000000";
    private static final String OTHER = "+998901112233";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RandomVerificationCodeSource.class, PresetVerificationCodeSource.class);

    @Test
    @DisplayName("profile production alone: no preset bean, RandomVerificationCodeSource is what's there")
    void productionAloneHasNoPresetBean() {
        runner.withPropertyValues("spring.profiles.active=production", PHONE_PROPERTY + "=" + PRESET)
                .run(context -> {
                    assertThat(context)
                            .as("the real production deployment never carries preprod, and the preset "
                                    + "must not exist there regardless of what the property says")
                            .hasNotFailed();
                    assertThat(context).doesNotHaveBean(PresetVerificationCodeSource.class);
                    assertThat(context.getBean(VerificationCodeSource.class))
                            .isInstanceOf(RandomVerificationCodeSource.class);
                });
    }

    @Test
    @DisplayName("profile production,preprod with the preset configured: the bean exists and answers the test number")
    void productionWithPreprodHasThePresetBean() {
        // deploy/compose.production.yml's actual pre-production shape:
        // SPRING_PROFILES_ACTIVE=production,preprod, the phone filled in and —
        // just as faithfully — the code variable left PRESENT and empty rather
        // than absent, exactly what ${HORECAOS_VERIFICATION_PRESET_CODE:-}
        // produces when the operator has not typed one. horecaos.environment is
        // left at this runner's default (see
        // productionWithPreprodAndProductionSecretNamespaceHasThePresetBean
        // below for the case where it reads "production", which is what it
        // actually reads on pre-production).
        runner.withPropertyValues(
                        "spring.profiles.active=production,preprod", PHONE_PROPERTY + "=" + PRESET, CODE_PROPERTY + "=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PresetVerificationCodeSource.class);
                    assertThat(context.getBean(VerificationCodeSource.class))
                            .as("@Primary picks the preset over the random source when both exist")
                            .isInstanceOf(PresetVerificationCodeSource.class);

                    PresetVerificationCodeSource source = context.getBean(PresetVerificationCodeSource.class);
                    assertThat(source.codeFor(PRESET).value())
                            .as("the default code, from a code property that is present but blank")
                            .isEqualTo("000000");
                    assertThat(source.codeFor(PRESET).requiresDelivery()).isFalse();

                    VerificationCodeSource.Code other = source.codeFor(OTHER);
                    assertThat(other.value()).isNotEqualTo("000000");
                    assertThat(VerificationCode.isWellFormed(other.value())).isTrue();
                    assertThat(other.requiresDelivery())
                            .as("every number besides the preset still needs a real transport")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("profile preprod with an empty preset phone: no bean, and the context still starts")
    void preprodWithEmptyPhoneHasNoBeanAndStarts() {
        // An unset HORECAOS_VERIFICATION_PRESET_PHONE arrives at the container as
        // HORECAOS_CUSTOMERS_VERIFICATION_PRESET_PHONE="" — present, not absent —
        // because deploy/compose.production.yml writes
        // ${HORECAOS_VERIFICATION_PRESET_PHONE:-}. This is the exact case
        // PresetVerificationCodePhoneConfiguredCondition exists for.
        runner.withPropertyValues("spring.profiles.active=preprod", PHONE_PROPERTY + "=")
                .run(context -> {
                    assertThat(context)
                            .as("a pre-production host that has not filled in the preset must still boot, "
                                    + "the same way an operator who left the variable blank intended")
                            .hasNotFailed();
                    assertThat(context).doesNotHaveBean(PresetVerificationCodeSource.class);
                    assertThat(context.getBean(VerificationCodeSource.class))
                            .isInstanceOf(RandomVerificationCodeSource.class);
                });
    }

    @Test
    @DisplayName("production,preprod with horecaos.environment=production: the bean still builds and answers the "
            + "preset number — this is pre-production's actual configuration")
    void productionWithPreprodAndProductionSecretNamespaceHasThePresetBean() {
        // Pre-production's OpenBao and data-encryption keys were provisioned
        // under the same "production" segment as the real deployment (it has no
        // namespace of its own), so horecaos.environment legitimately reads
        // "production" on that host too — the same value the real production
        // host reads. PresetVerificationCodeSource deliberately does not
        // consult this property for exactly that reason: it cannot tell the two
        // hosts apart. This proves the bean builds under pre-production's real
        // configuration, not just under a runner default that happens to differ
        // from "production".
        runner.withPropertyValues(
                        "spring.profiles.active=production,preprod",
                        PHONE_PROPERTY + "=" + PRESET,
                        CODE_PROPERTY + "=424242",
                        ENVIRONMENT_PROPERTY + "=production")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PresetVerificationCodeSource.class);
                    assertThat(context.getBean(VerificationCodeSource.class))
                            .isInstanceOf(PresetVerificationCodeSource.class);

                    PresetVerificationCodeSource source = context.getBean(PresetVerificationCodeSource.class);
                    assertThat(source.codeFor(PRESET).value()).isEqualTo("424242");
                    assertThat(source.codeFor(PRESET).requiresDelivery()).isFalse();
                });
    }
}
