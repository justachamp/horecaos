package uz.horecaos.platform.kitchen.api;

import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The kitchen module's ADR 0030 configuration keys.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in
 * {@code tenancy.domain.configuration}, which is internal to the tenancy module; importing it here is
 * not possible and importing this from there would make the two modules cyclic. The registry therefore
 * carries an identical declaration, and {@code KitchenConfigurationKeyTests} fails the build if the two
 * ever drift apart, the same arrangement every other module's keys use.
 */
public final class KitchenConfigurationKeys {

    private KitchenConfigurationKeys() {}

    /** The code both declarations share. */
    public static final String DISPLAY_NOT_SEEN_AFTER_MINUTES_CODE = "kitchen.display.not_seen_after_minutes";

    /**
     * Minutes without a read after which Kitchen → Devices shows a wall display as "not seen" (ADR 0151).
     *
     * <p>A wall display reads its projection every ten seconds, and the read stamps {@code
     * kitchen.device_displays.last_read_at} at most once a minute, so five minutes is a screen that has
     * missed several polls in a row: powered off, off the network, or showing a cached error. Not tenant
     * visible: it is the platform's judgement of what "dark" means, adjustable per tenant or branch by
     * whoever runs the platform, and is not a control an operator is expected to tune.
     */
    public static final ConfigurationKey<Integer> DISPLAY_NOT_SEEN_AFTER_MINUTES = ConfigurationKey.of(
                    DISPLAY_NOT_SEEN_AFTER_MINUTES_CODE, Integer.class)
            .defaultValue(5)
            .ownedBy("kitchen")
            .describedAs("Minutes without a read after which a kitchen wall display is shown as not seen.")
            .build();
}
