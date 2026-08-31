package uz.horecaos.platform.migration.application.importing;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.migration.api.LegacyRecord;

/**
 * One page of legacy rows, and whether there are more (ADR 0024).
 *
 * @param records  the rows, in stable-key order. Empty on the page past the end
 * @param nextKey  the exclusive lower bound for the next page: the last row's
 *                 stable key, or the previous bound unchanged when the page was
 *                 empty. Never derived by the caller, because the reader is the
 *                 only thing that knows how the source ordered them. Null only
 *                 when the page is empty and the previous bound was itself the
 *                 start of the source
 * @param exhausted whether the source had fewer rows than were asked for, which
 *                 is the only honest end-of-source signal available without a
 *                 second query. A page that is exactly full is not the last page,
 *                 even when it happens to be
 */
public record SourcePage(List<LegacyRecord> records, @Nullable String nextKey, boolean exhausted) {

    public SourcePage {
        Objects.requireNonNull(records, "A page has a row list, possibly empty");
        records = List.copyOf(records);
        if (!records.isEmpty() && nextKey == null) {
            throw new IllegalArgumentException("A page with rows has a next key, or a restart re-reads it forever");
        }
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public int size() {
        return records.size();
    }
}
