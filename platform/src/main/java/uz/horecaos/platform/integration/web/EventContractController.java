package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The ADR 0032 event contract registry, read-only (control-plane IA 3.4).
 *
 * <p>{@link EventCatalog} has always been the enforcement point for "no event
 * may be published without a documented contract" — every producer resolves
 * its contract through {@link EventCatalog#require}, and a build fails
 * before ever reaching production if the entry, its JSON Schema, or its
 * catalogue row in {@code docs/domains/events.md} are missing. Nothing
 * served that registry over HTTP before this, the same gap {@link
 * uz.horecaos.platform.iam.web.CapabilityRegistryController} closed for the
 * ADR 0025 capability vocabulary.
 *
 * <p><strong>Named gap.</strong> IA 3.4 also asks for "adapter versions,
 * deprecations, consumer compatibility" — provider adapter versioning
 * distinct from the event contract catalogue below. No such registry exists
 * anywhere in this codebase: {@code ProviderCapabilityCatalog} declares
 * capabilities, not versions, and nothing tracks a deprecation or a consumer
 * compatibility matrix. This endpoint returns the event/schema contract
 * half of the row, which is real, and invents nothing for the other half.
 */
@RestController
@RequestMapping("/api/v1/control-plane/event-contracts")
@Tag(name = "Event contracts", description = "The ADR 0032 code-owned event/schema contract registry")
public class EventContractController {

    @GetMapping
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every registered event contract",
            description = "Code-owned (ADR 0032): a producer cannot publish an event with no entry "
                    + "here. Static within a build, so there is nothing to paginate.")
    List<EventContractResponse> list() {
        return EventCatalog.all().stream()
                .map(EventContractResponse::of)
                .sorted((left, right) -> left.eventType().compareTo(right.eventType()))
                .toList();
    }

    /** One registered contract, as the catalogue declares it. */
    public record EventContractResponse(
            String eventType,
            int eventVersion,
            String producingModule,
            String topic,
            String partitionKey,
            String retention,
            String classification,
            String description) {

        static EventContractResponse of(EventContract contract) {
            return new EventContractResponse(
                    contract.eventType(),
                    contract.eventVersion(),
                    contract.producingModule(),
                    contract.topic(),
                    contract.partitionKey(),
                    contract.retention().name(),
                    contract.classification().name(),
                    contract.description());
        }
    }
}
