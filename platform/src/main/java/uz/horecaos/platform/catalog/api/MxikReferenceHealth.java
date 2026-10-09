package uz.horecaos.platform.catalog.api;

import org.jspecify.annotations.Nullable;

/**
 * What a platform check needs to know about the national ИКПУ/MXIK list (ADR 0173 Decision 9): whether it is
 * loaded, how old it is, and whether it matters yet.
 *
 * <p>A port the catalog implements and the notifications module reads, so that the daily check which raises
 * "the list is not loaded" and "the list is stale" lives with the incidents it raises and the catalog depends
 * on nothing of the alert module. (The first cut had the catalog raise them itself, and the module cycle that
 * made, catalog to notifications and back through the inventory digest's item names, is what this replaces.)
 */
public interface MxikReferenceHealth {

    Health health();

    /**
     * @param loaded              the state row exists, or the list holds any row because a load predates the state
     * @param stale               the list is older than {@code catalog.mxik_reference_max_age_days}
     * @param ageDays             whole days since the last apply, or null when none was recorded
     * @param maxAgeDays          the age key in force, or null when none resolves
     * @param validRows           codes valid today
     * @param withdrawnRows       codes whose withdrawal date has been reached
     * @param anyTenantClassified whether any tenant holds a classified node, which is what makes an absent list a
     *                            problem rather than a fact
     */
    record Health(
            boolean loaded,
            boolean stale,
            @Nullable Long ageDays,
            @Nullable Integer maxAgeDays,
            int validRows,
            int withdrawnRows,
            boolean anyTenantClassified) {}
}
