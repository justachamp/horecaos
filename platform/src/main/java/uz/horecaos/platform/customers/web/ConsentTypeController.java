package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.application.ConsentTypeService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore.ConsentTypeRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The tenant-wide consent-purpose registry (Settings 10.11, ADR 0109) —
 * genuinely missing before this wave: {@code customer.consent_decisions} has
 * recorded a decision's purpose as free text since V0017, and there was no
 * table anywhere naming what a purpose code means or which language it reads
 * in. See {@link ConsentTypeService}'s own doc for why this is a reference
 * catalogue and not an enforcement point.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/consent-types")
@Tag(name = "Consent types", description = "The tenant's own consent-purpose registry")
public class ConsentTypeController {

    private final ConsentTypeService consentTypes;
    private final CurrentActor currentActor;

    public ConsentTypeController(ConsentTypeService consentTypes, CurrentActor currentActor) {
        this.consentTypes = consentTypes;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "Every consent purpose this tenant has defined",
            description = "Bootstraps the code-owned default catalogue on this tenant's first "
                    + "read, so a tenant with no rows yet still sees the two purposes already in "
                    + "production use (MARKETING_PROMOTIONS, TERMS_OF_SERVICE) rather than an "
                    + "empty registry.")
    List<ConsentTypeResponse> list(@PathVariable UUID tenantId) {
        return consentTypes.list(tenantId, actor()).stream()
                .map(ConsentTypeResponse::of)
                .toList();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /** One consent purpose, as the registry reads it. */
    public record ConsentTypeResponse(
            UUID id,
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String description,
            boolean channelSpecific,
            String policyVersion,
            boolean active,
            Instant updatedAt) {

        static ConsentTypeResponse of(ConsentTypeRow row) {
            return new ConsentTypeResponse(
                    row.id(),
                    row.code(),
                    row.labelRu(),
                    row.labelUz(),
                    row.labelEn(),
                    row.description(),
                    row.channelSpecific(),
                    row.policyVersion(),
                    row.active(),
                    row.updatedAt());
        }
    }
}
