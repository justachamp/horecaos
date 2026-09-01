package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.conversations.domain.FlowDocument;
import uz.horecaos.platform.conversations.domain.FlowDocumentException;
import uz.horecaos.platform.conversations.domain.FlowDocumentParser;
import uz.horecaos.platform.conversations.domain.FlowDocumentValidator;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * The control-plane authoring surface (ADR 0059: "authored as configuration
 * through the control-plane... a broken flow must never be discoverable only
 * at runtime"). A document is parsed and validated before a single byte
 * reaches the database — {@link FlowDocumentException} carries every problem
 * found, and none of them is persisted.
 */
@Service
public class FlowDocumentService {

    private final FlowDocumentRepository documents;
    private final AuditRecorder audit;
    private final Clock clock;

    FlowDocumentService(FlowDocumentRepository documents, AuditRecorder audit, Clock clock) {
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
    }

    public List<FlowDocumentView> list(UUID tenantId, UUID brandId) {
        return documents.list(tenantId, brandId).stream()
                .map(FlowDocumentView::of)
                .toList();
    }

    /**
     * Publishes the next version of {@code flowKey}: validates, inserts, and
     * — when {@code activate} is true — deactivates the previously active
     * version in the same transaction, so a reader never observes zero or two
     * active versions.
     *
     * @throws FlowDocumentException the document is malformed or invalid;
     *                                nothing is persisted
     * @throws IllegalArgumentException the YAML's own {@code flowKey} does not
     *                                   match {@code flowKey}
     */
    @Transactional
    public FlowDocumentView author(
            UUID tenantId,
            UUID brandId,
            String flowKey,
            String documentYaml,
            @Nullable String description,
            boolean activate,
            String authoredBy,
            String reason) {
        FlowDocument parsed = FlowDocumentParser.parse(documentYaml);
        FlowDocumentValidator.validate(parsed);
        if (!parsed.flowKey().equals(flowKey)) {
            throw new IllegalArgumentException(
                    "The document's own flowKey \"%s\" does not match \"%s\"".formatted(parsed.flowKey(), flowKey));
        }

        int version = documents.nextVersion(tenantId, brandId, flowKey);
        if (activate) {
            documents.deactivateActive(tenantId, brandId, flowKey);
        }
        FlowDocumentRepository.Row row =
                documents.insert(tenantId, brandId, flowKey, version, documentYaml, description, authoredBy, activate);

        audit.record(AuditFact.of("conversations.flow_document.authored", AuditClass.BUSINESS)
                .by(ActorRef.user(authoredBy, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("conversations.flow_document", row.id())
                .because(reason)
                .changed(java.util.Map.of(
                        "flowKey", flowKey, "version", String.valueOf(version), "active", String.valueOf(activate)))
                .correlatedBy(row.id().toString())
                .occurredAt(clock.instant())
                .build());

        return FlowDocumentView.of(row);
    }

    Optional<FlowDocumentRepository.Row> activeRow(UUID tenantId, UUID brandId, String flowKey) {
        return documents.findActive(tenantId, brandId, flowKey);
    }

    /** The parsed document a run pinned itself to at start — by id, never "whichever is active now". */
    Optional<FlowDocument> parsedById(UUID tenantId, UUID documentId) {
        return documents.findById(tenantId, documentId).map(row -> FlowDocumentParser.parse(row.documentYaml()));
    }
}
