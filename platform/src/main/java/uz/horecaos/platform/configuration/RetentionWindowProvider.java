package uz.horecaos.platform.configuration;

import java.util.UUID;

/**
 * A job that applies a tenant-visible retention setting, asked what it will do for one tenant (Settings 10.11).
 *
 * <p>The job implements this itself, so the answer shown on the retention card is computed by the same code, with the same
 * floor and the same fallbacks, as the sweep that acts on it. A separate calculation in a controller would be one more
 * place for the two to drift.
 */
public interface RetentionWindowProvider {

    /** The window this job applies to {@code tenantId}'s data. */
    RetentionWindow windowFor(UUID tenantId);
}
