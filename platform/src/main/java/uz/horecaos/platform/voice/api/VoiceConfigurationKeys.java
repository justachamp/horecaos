package uz.horecaos.platform.voice.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The voice module's ADR 0030 configuration keys.
 *
 * <p>There is one so far (ADR 0175): the longest interval from a call's {@code OFFERED} event to
 * its first {@code ANSWERED} event that the day close still believes. A longer pair is almost
 * certainly two different calls joined by a reused provider call id, and it would dominate an
 * average. The close reads it once per tenant per close.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in
 * {@code tenancy.domain.configuration}, which is internal to the tenancy module; importing it here
 * is not possible and importing this from there would make the two modules cyclic. The registry
 * therefore carries an identical declaration, and {@code VoiceConfigurationKeyTests} fails the
 * build if the two ever drift apart.
 */
public final class VoiceConfigurationKeys {

    /** The code both declarations share. */
    public static final String RING_TIME_CEILING_SECONDS_CODE = "voice.ring_time_ceiling_seconds";

    /**
     * Seconds: the longest offered-to-answered interval still counted as one call's ring time.
     * 1800 (half an hour), the proposed default of ADR 0175's open input.
     */
    public static final ConfigurationKey<Integer> RING_TIME_CEILING_SECONDS = ConfigurationKey.of(
                    RING_TIME_CEILING_SECONDS_CODE, Integer.class)
            .defaultValue(1800)
            .ownedBy("voice")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("The longest interval, in seconds, from a call's arrival to its first answer "
                    + "that still counts as one call's answer speed; a longer pair is treated as two "
                    + "calls and left out of the average.")
            .build();

    private VoiceConfigurationKeys() {}
}
