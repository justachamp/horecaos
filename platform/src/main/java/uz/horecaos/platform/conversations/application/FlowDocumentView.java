package uz.horecaos.platform.conversations.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A published flow document version, as {@link FlowDocumentService} exposes it to the control-plane. */
public record FlowDocumentView(
        UUID id,
        UUID tenantId,
        UUID brandId,
        String flowKey,
        int version,
        String documentYaml,
        boolean active,
        @Nullable String description,
        String authoredBy,
        Instant createdAt) {

    static FlowDocumentView of(FlowDocumentRepository.Row row) {
        return new FlowDocumentView(
                row.id(),
                row.tenantId(),
                row.brandId(),
                row.flowKey(),
                row.version(),
                row.documentYaml(),
                row.active(),
                row.description(),
                row.authoredBy(),
                row.createdAt());
    }
}
