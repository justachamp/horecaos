package uz.horecaos.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.TrackRetentionFloor;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The two ADR 0030 keys ADR 0045 owns, declared twice and kept identical.
 *
 * <p>The registry the startup validator consults lives inside the tenancy
 * module and cannot be imported from here; a reference the other way would make
 * the modules cyclic. So each key exists in both places, and this test is what
 * stops them drifting — the same arrangement ADR 0021's enforcement ceiling uses,
 * and it exists because the failure mode is silent: a default that differs
 * between the two would resolve one way in the telemetry module and validate
 * another way at startup, and nobody would find out until a tenant's configured
 * retention was measured against the wrong number.
 */
class TelemetryConfigurationKeyTests {

    @Test
    @DisplayName("the collection gate declaration is identical on both sides")
    void theGateKeysAgree() {
        assertThat(registered(TelemetryConfigurationKeys.COLLECTION_GATE_CODE))
                .isEqualTo(TelemetryConfigurationKeys.COLLECTION_GATE);
    }

    @Test
    @DisplayName("the track retention declaration is identical on both sides")
    void theRetentionKeysAgree() {
        assertThat(registered(TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE))
                .isEqualTo(TelemetryConfigurationKeys.TRACK_RETENTION_DAYS);
    }

    @Test
    @DisplayName("the shipped defaults are the ones ADR 0045 decided")
    void theDefaultsAreTheDecision() {
        assertThat(TelemetryConfigurationKeys.COLLECTION_GATE.defaultValue())
                .as("the dispatcher board must see idle couriers to assign them")
                .isEqualTo(CollectionGate.ON_DUTY.name());

        assertThat(TelemetryConfigurationKeys.TRACK_RETENTION_DAYS.defaultValue())
                .isEqualTo(TrackRetentionFloor.CONFIGURED_TRACK_RETENTION_DAYS)
                .isEqualTo(30);
    }

    @Test
    @DisplayName("retention is not settable per brand or per branch")
    void retentionIsATenantLevelCalendarQuestion() {
        // A branch setting its own retention would make "how long are couriers
        // kept" a question with a different answer per polygon on a map, and the
        // floor it has to clear is an ADR 0042 calendar the tenant owns.
        assertThat(TelemetryConfigurationKeys.TRACK_RETENTION_DAYS.settableScopes())
                .containsExactlyInAnyOrder(
                        uz.horecaos.platform.iam.api.ResourceScope.ScopeType.PLATFORM,
                        uz.horecaos.platform.iam.api.ResourceScope.ScopeType.TENANT);

        // The gate is the opposite: a branch running two scooters and a branch
        // that sends everything to Yandex have different answers, and forcing one
        // is how the narrower gate never gets used anywhere.
        assertThat(TelemetryConfigurationKeys.COLLECTION_GATE.settableScopes())
                .contains(uz.horecaos.platform.iam.api.ResourceScope.ScopeType.LOCATION);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
