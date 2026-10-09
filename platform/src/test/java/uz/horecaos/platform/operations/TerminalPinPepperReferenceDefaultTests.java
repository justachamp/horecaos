package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.PlaceholderResolutionException;

/**
 * ADR 0155's terminal PIN pepper reference has no default outside a local profile (ADR 0028), for the reason the handover
 * pepper's has none: the reference carries the environment segment the secret path is built from, so a default would resolve
 * against whichever environment it names, and a pepper every environment shares keys every PIN digest in every environment.
 * A production start without {@code HORECAOS_TERMINAL_PIN_PEPPER_REF} fails naming the variable.
 *
 * <p>Asserted against the real {@code application.yml}, because the file is the thing that would be wrong.
 */
class TerminalPinPepperReferenceDefaultTests {

    private static final String KEY = "horecaos.terminal.pin-pepper-reference";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("a laptop and the test suite get the local reference")
    void theDefaultProfileResolvesTheLocalReference() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty(KEY))
                .as("no profile set is the default profile, which is how a laptop runs")
                .isEqualTo("horecaos:local:data_encryption:platform:terminal-pin-pepper"));
    }

    @Test
    @DisplayName("the local reference does not follow the application to a deployment")
    void aDeploymentProfileHasNoFallback() {
        runner.withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThatThrownBy(
                                () -> context.getEnvironment().getProperty(KEY))
                        .as("a deployment supplies HORECAOS_TERMINAL_PIN_PEPPER_REF or does not start")
                        .isInstanceOf(PlaceholderResolutionException.class)
                        // The message an operator reads at 3am. It names the variable
                        // that is missing, which is the fact they can act on; a
                        // reference that resolved to a path in the wrong environment
                        // named nothing and failed somewhere else entirely.
                        .hasMessageContaining("HORECAOS_TERMINAL_PIN_PEPPER_REF")
                        .hasMessageNotContaining("horecaos:local"));
    }

    @Test
    @DisplayName("no deployment profile inherits the local reference by another name")
    void noDeploymentProfileInheritsIt() {
        // `production` is not the only non-local profile that could exist, and the
        // activation expression is a list rather than a negation — so a profile
        // nobody has thought of yet fails closed rather than open.
        for (String profile : List.of("staging", "pilot", "production,observability")) {
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThatThrownBy(
                                    () -> context.getEnvironment().getProperty(KEY))
                            .isInstanceOf(PlaceholderResolutionException.class));
        }
    }
}
