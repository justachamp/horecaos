package uz.horecaos.platform.customers.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant.ExportSubject;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * The one way an export participant opens a protected column: through {@code FieldProtection.reveal} with the row it was
 * written for and the purpose the producing person declared. Null stays null, so "this column holds nothing" is an answer
 * the bundle can give.
 */
public final class ExportReveal {

    private ExportReveal() {}

    /**
     * Renders a row a store read column by column, opening every column named {@code *_encrypted} or {@code *_protected}
     * under the row it was written for. The key loses the suffix ({@code note_encrypted} becomes {@code note}). The row's
     * id, for the associated data the protection binds a ciphertext to, is the value of {@code idColumn}.
     */
    public static Map<String, @Nullable Object> row(
            FieldProtection protection,
            ExportSubject subject,
            String table,
            String idColumn,
            Map<String, ? extends @Nullable Object> raw) {
        UUID rowId = (UUID) java.util.Objects.requireNonNull(raw.get(idColumn), idColumn);
        Map<String, @Nullable Object> opened = new LinkedHashMap<>();
        raw.forEach((column, value) -> {
            if (column.endsWith("_encrypted") || column.endsWith("_protected")) {
                String plain = column.substring(0, column.lastIndexOf('_'));
                opened.put(plain, text(protection, subject, table, column, rowId, value));
            } else {
                opened.put(column, value);
            }
        });
        return ExportRow.render(opened);
    }

    /**
     * @param stored the column as the store returned it (the serialized protected value), or null
     * @return the plaintext, or null when the column held nothing
     */
    public static @Nullable String text(
            FieldProtection protection,
            ExportSubject subject,
            String table,
            String column,
            UUID rowId,
            @Nullable Object stored) {
        if (stored == null) {
            return null;
        }
        return protection.reveal(
                subject.tenantId(),
                ProtectedValue.deserialize((String) stored),
                new RecordRef(table, column, rowId),
                subject.purpose());
    }
}
