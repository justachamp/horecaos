package uz.horecaos.platform.migration.infrastructure.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where the previous page of a migration list ended (ADR 0031).
 *
 * <p>Every list here is keyset-paginated on the pair its index is ordered by,
 * never on an offset. The control plane is read while it is being written — a
 * backfill files quarantine items and a reconciliation records results under the
 * operator who is paging through them — and an offset silently skips rows when
 * the collection grows underneath the reader. In this schema the row skipped
 * would be a legacy record nobody then accounts for.
 *
 * <p>The id is part of the key rather than decoration: two runs started in the
 * same millisecond order arbitrarily by timestamp alone, so a page boundary
 * falling between them would drop one of the two.
 */
public record MigrationPageCursor(Instant at, UUID id) {

    public MigrationPageCursor {
        Objects.requireNonNull(at, "A cursor timestamp is required");
        Objects.requireNonNull(id, "A cursor id is required");
    }

    /**
     * Binds the keyset parameters, treating a null cursor as the first page.
     *
     * <p>A {@link HashMap} rather than {@code Map.of}, which rejects the nulls
     * that the first page of every list is made of.
     */
    static Map<String, Object> params(@Nullable MigrationPageCursor cursor) {
        Map<String, Object> params = new HashMap<>();
        params.put("afterAt", cursor == null ? null : MigrationColumns.utc(cursor.at()));
        params.put("afterId", cursor == null ? null : cursor.id());
        return params;
    }
}
