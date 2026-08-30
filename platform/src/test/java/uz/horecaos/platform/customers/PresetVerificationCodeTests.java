package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.horecaos.platform.customers.application.RandomVerificationCodeSource;
import uz.horecaos.platform.customers.application.VerificationCodeSource.Code;
import uz.horecaos.platform.customers.domain.VerificationCode;
import uz.horecaos.platform.customers.infrastructure.security.PresetVerificationCodeGuard;
import uz.horecaos.platform.customers.infrastructure.security.PresetVerificationCodeSource;

/**
 * The number that signs in with a fixed code, and the three things that stop it
 * ever being one in production (ADR 0051).
 *
 * <p>This is the most dangerous feature in the change and the assertions are
 * written accordingly. A fixed one-time code reaching a deployment is not a
 * weakened control: it is a complete authentication bypass for every customer of
 * every tenant, available to anybody who can type a phone number into a form. So
 * the tests are about the refusals, not about the convenience.
 *
 * <p>No Spring context. The guard is a pure function of an {@link org.springframework.core.env.Environment}
 * precisely so that this can be asserted against a production-shaped profile
 * without booting one — a guard whose test is slow enough to skip stops being a
 * guard.
 */
class PresetVerificationCodeTests {

    private static final String PRESET = "+998000000000";

    private final RandomVerificationCodeSource random = new RandomVerificationCodeSource();

    // ---------------------------------------------------------------- the source

    @Test
    @DisplayName("the configured number gets the configured code, and nothing is sent")
    void thePresetNumberIsAnswered() {
        Code code = source(PRESET, "424242").codeFor(PRESET);

        assertThat(code.value()).isEqualTo("424242");
        assertThat(code.requiresDelivery())
                .as("there is no gateway on a laptop, so a preset that asked to be sent "
                        + "would fail at exactly the point it exists to get past")
                .isFalse();
    }

    @Test
    @DisplayName("every other number still draws a random code and still asks to be sent")
    void everybodyElseIsUnaffected() {
        Code code = source(PRESET, "424242").codeFor("+998901112233");

        assertThat(code.requiresDelivery())
                .as("if the preset ever widened, an unconfigured SMS path would start "
                        + "looking like a working one — which is the failure "
                        + "VerificationTransportGuard exists to prevent")
                .isTrue();
        assertThat(VerificationCode.isWellFormed(code.value())).isTrue();
        assertThat(code.value()).isNotEqualTo("424242");
    }

    @Test
    @DisplayName("the preset is matched after canonicalisation, not as typed")
    void spellingDoesNotMatter() {
        PresetVerificationCodeSource source = source(PRESET, "424242");

        // The caller canonicalises before it reaches here, so what this asserts is
        // that the *configured* value was canonicalised too. Configured as a
        // national number and compared against E.164, an uncanonicalised preset
        // silently never matches and the owner cannot sign in with no error
        // anywhere to explain it.
        assertThat(source("000 00 00 00", "424242").codeFor(PRESET).value()).isEqualTo("424242");
        assertThat(source.codeFor(PRESET).requiresDelivery()).isFalse();
    }

    @Test
    @DisplayName("a code that is not six digits fails at startup, not at sign-in")
    void aMistypedCodeIsRefusedAtConstruction() {
        assertThatThrownBy(() -> source(PRESET, "42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PresetVerificationCodeSource.CODE_PROPERTY);
    }

    @Test
    @DisplayName("a number that is not an Uzbek mobile fails at startup too")
    void aMistypedNumberIsRefusedAtConstruction() {
        assertThatThrownBy(() -> source("not-a-number", "424242")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the preset source cannot exist outside a local profile")
    void theSourceIsProfileBound() {
        org.springframework.context.annotation.Profile profile =
                PresetVerificationCodeSource.class.getAnnotation(org.springframework.context.annotation.Profile.class);

        assertThat(profile)
                .as("the first of the three locks. Without it the guard is the only one, "
                        + "and a guard can be disabled by removing a bean")
                .isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("local", "test", "default");
    }

    // ----------------------------------------------------------------- the guard

    @Test
    @DisplayName("a non-local profile refuses to start when a preset code is configured")
    void aRealProfileWillNotStartWithABypass() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        production.setProperty(PresetVerificationCodeSource.PHONE_PROPERTY, PRESET);

        assertThatThrownBy(() -> verify(production))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PresetVerificationCodeSource.PHONE_PROPERTY)
                .hasMessageContaining("production");
    }

    @Test
    @DisplayName("the code alone is enough to refuse, without a number beside it")
    void eitherPropertyIsEnoughToRefuse() {
        // Checked separately because the bean is conditional on the phone property
        // alone. A deployment carrying only the code would create no preset source
        // and would still be a deployment somebody has half-configured a bypass
        // into; refusing on either is what makes the guard about the intent rather
        // than about the wiring.
        MockEnvironment staging = new MockEnvironment();
        staging.setActiveProfiles("staging");
        staging.setProperty(PresetVerificationCodeSource.CODE_PROPERTY, "000000");

        assertThatThrownBy(() -> verify(staging)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a non-local profile with no preset starts normally")
    void aRealProfileWithoutOneIsFine() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        verify(production);
    }

    @Test
    @DisplayName("a blank value is not a configured one")
    void aBlankPropertyIsNotConfigured() {
        // An unset environment variable expands to an empty string in a compose
        // file, which is the ordinary way this property arrives at a deployment
        // that does not want it. Treating that as "configured" would refuse to
        // start every environment that merely mentions the variable.
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        production.setProperty(PresetVerificationCodeSource.PHONE_PROPERTY, "  ");

        verify(production);
    }

    @Test
    @DisplayName("every local profile starts with one, which is what it is for")
    void theLocalProfilesAreAllowed() {
        for (String profile : new String[] {"local", "test", "default"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            environment.setProperty(PresetVerificationCodeSource.PHONE_PROPERTY, PRESET);

            verify(environment);
        }
    }

    @Test
    @DisplayName("no active profile at all is local, because that is what a laptop is")
    void noProfileIsLocal() {
        MockEnvironment none = new MockEnvironment();
        none.setProperty(PresetVerificationCodeSource.PHONE_PROPERTY, PRESET);

        verify(none);
    }

    @Test
    @DisplayName("one local profile beside a real one is not local")
    void aMixedProfileSetIsNotLocal() {
        // "local,production" is how somebody reuses a laptop's compose file
        // against a real database. Allowing it because one of the names is
        // familiar is exactly the reasoning this guard has to refuse.
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("local", "production");
        mixed.setProperty(PresetVerificationCodeSource.PHONE_PROPERTY, PRESET);

        assertThatThrownBy(() -> verify(mixed)).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ helpers

    private PresetVerificationCodeSource source(String phone, String code) {
        return new PresetVerificationCodeSource(phone, code, random);
    }

    private static void verify(org.springframework.core.env.Environment environment) {
        PresetVerificationCodeGuard.verify(environment);
    }
}
