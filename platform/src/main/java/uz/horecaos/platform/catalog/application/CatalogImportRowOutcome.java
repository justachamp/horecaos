package uz.horecaos.platform.catalog.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What happened — or, on a dry run, what would have happened — to one row of
 * a catalog CSV/Excel import (row 4.5b).
 *
 * <p>The same shape either way, the same "computed identically in both modes"
 * property {@code CustomerCsvImportRowOutcome}'s own doc states: {@link
 * CatalogImportRowService} decides {@link Type#CREATED}/{@link
 * Type#UPDATED}/{@link Type#SKIPPED} by the same field comparison whether or
 * not it is about to write anything, and only the ids behind {@link
 * #productId}/{@link #variantId} differ (real on apply, {@code null} for a
 * would-be {@link Type#CREATED} on a dry run — nothing was actually created
 * to name).
 */
public record CatalogImportRowOutcome(
        Type type,
        @Nullable UUID productId,
        @Nullable UUID variantId,
        @Nullable CatalogImportRowErrorReason errorReason) {

    public static CatalogImportRowOutcome created(@Nullable UUID productId, @Nullable UUID variantId) {
        return new CatalogImportRowOutcome(Type.CREATED, productId, variantId, null);
    }

    public static CatalogImportRowOutcome updated(UUID productId, UUID variantId) {
        return new CatalogImportRowOutcome(Type.UPDATED, productId, variantId, null);
    }

    public static CatalogImportRowOutcome skipped(UUID productId, UUID variantId) {
        return new CatalogImportRowOutcome(Type.SKIPPED, productId, variantId, null);
    }

    public static CatalogImportRowOutcome error(CatalogImportRowErrorReason reason) {
        return new CatalogImportRowOutcome(Type.ERROR, null, null, reason);
    }

    public enum Type {
        CREATED,
        UPDATED,
        SKIPPED,
        ERROR
    }
}
