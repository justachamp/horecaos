package uz.qoida.platform.telemetry.api;

import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.tenancy.api.ConfigurationKey;
import uz.qoida.platform.telemetry.domain.CollectionGate;
import uz.qoida.platform.telemetry.domain.TrackRetentionFloor;

/**
 * The telemetry module's ADR 0030 configuration keys (ADR 0045).
 *
 * <p>There are exactly two, and ADR 0045 names them together for a reason: they
 * are the whole of what a narrower answer from legal would move. The statutory
 * basis for courier location processing is an open input on that ADR, and it is
 * recorded there as <em>not structural</em> precisely because a narrower answer
 * changes these two values and no table, endpoint, capability, or transport.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator
 * consults lives in {@code tenancy.domain.configuration}, which is internal to
 * the tenancy module; importing it here is not possible and importing this from
 * there would make the two modules cyclic. The registry carries an identical
 * declaration, and {@code TelemetryConfigurationKeyTests} fails the build if the
 * two ever drift apart — the same arrangement ADR 0021's enforcement ceiling
 * already uses.
 */
public final class TelemetryConfigurationKeys {

    /** The codes both declarations share. */
    public static final String COLLECTION_GATE_CODE = "telemetry.courier_collection_gate";
    public static final String TRACK_RETENTION_DAYS_CODE = "telemetry.track_retention_days";

    /**
     * When telemetry is collected inside an open duty session.
     *
     * <p>Settable down to a location, because service models differ within one
     * tenant: a branch running its own two scooters and a branch that dispatches
     * everything to Yandex have different answers, and forcing one is how the
     * narrower gate never gets used anywhere.
     */
    public static final ConfigurationKey<String> COLLECTION_GATE =
            ConfigurationKey.of(COLLECTION_GATE_CODE, String.class)
                    .defaultValue(CollectionGate.ON_DUTY.name())
                    .ownedBy("telemetry")
                    .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION)
                    .describedAs("When courier telemetry is collected inside an open duty session. "
                            + "ON_DUTY collects for the whole session so a dispatcher can see idle "
                            + "couriers; ON_ASSIGNMENT collects only while carrying an order.")
                    .build();

    /**
     * How long a track survives at coordinate precision.
     *
     * <p>Settable at the platform and at a tenant and no lower. A brand or a
     * branch setting its own retention would make "how long are couriers kept"
     * a question with a different answer per polygon on a map, and the floor it
     * has to clear — settlement period plus dispute window — is a tenant-level
     * calendar in ADR 0042. The startup check refuses a production profile whose
     * configured values breach that floor, at any scope one is stored at.
     */
    public static final ConfigurationKey<Integer> TRACK_RETENTION_DAYS =
            ConfigurationKey.of(TRACK_RETENTION_DAYS_CODE, Integer.class)
                    .defaultValue(TrackRetentionFloor.CONFIGURED_TRACK_RETENTION_DAYS)
                    .ownedBy("telemetry")
                    .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
                    .describedAs("Days a courier's track is kept at coordinate precision before its "
                            + "daily partition is dropped. Must be at least the ADR 0042 settlement "
                            + "period plus the statement dispute window; a production start refuses "
                            + "a value below that floor.")
                    .build();

    private TelemetryConfigurationKeys() {
    }
}
