package uz.horecaos.platform.commercial.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.api.CommercialConfigurationKeys;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The enforcement ceiling is declared twice and must stay one key
 * (ADR 0021, ADR 0030).
 *
 * <p>ADR 0030's registry lives inside the tenancy module and the commercial
 * module cannot import it; a reference the other way would make the two modules
 * cyclic. So the declaration exists in both places, and this test is what stops
 * that from becoming two different settings with one name — the exact failure
 * the registry exists to prevent.
 *
 * <p>A test rather than a comment because the two files are edited by different
 * people for different reasons, and a comment has never stopped anybody.
 */
class EnforcementCeilingKeyTests {

    @Test
    void theRegistryAndTheCommercialModuleDeclareTheSameKey() {
        ConfigurationKey<?> registered =
                ConfigurationKeys.require(CommercialConfigurationKeys.ENFORCEMENT_CEILING_CODE);
        ConfigurationKey<String> used = CommercialConfigurationKeys.ENFORCEMENT_CEILING;

        assertThat(registered.valueType()).isEqualTo(used.valueType());
        assertThat(registered.defaultValue()).isEqualTo(used.defaultValue());
        assertThat(registered.settableScopes()).isEqualTo(used.settableScopes());
        assertThat(registered.owningModule()).isEqualTo(used.owningModule());
        assertThat(registered.explicitNullTerminates()).isEqualTo(used.explicitNullTerminates());
    }

    @Test
    void theDefaultCeilingIsMeterOnly() {
        // The single most consequential line in the module. The pilot runs with
        // no commercial enforcement, and every tenant that has not been
        // deliberately raised runs the same way.
        assertThat(CommercialConfigurationKeys.ENFORCEMENT_CEILING.defaultValue())
                .isEqualTo(EnforcementMode.METER_ONLY.name());
        assertThat(EnforcementMode.valueOf(Objects.requireNonNull(
                                        ConfigurationKeys.require(CommercialConfigurationKeys.ENFORCEMENT_CEILING_CODE)
                                                .defaultValue(),
                                        "the registry declares a default for this key")
                                .toString())
                        .canRefuse())
                .as("a default that could refuse would enforce limits nobody has approved")
                .isFalse();
    }
}
