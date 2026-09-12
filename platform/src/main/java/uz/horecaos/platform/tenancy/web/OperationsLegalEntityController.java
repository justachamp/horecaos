package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.LegalEntityService;
import uz.horecaos.platform.tenancy.application.LegalEntityService.AssignLocationCommand;
import uz.horecaos.platform.tenancy.application.LegalEntityService.RegisterLegalEntityCommand;
import uz.horecaos.platform.tenancy.domain.LegalEntity;
import uz.horecaos.platform.tenancy.domain.LocationFiscalAssignment;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's own view of ADR 0038's legal-entity registry, mirrored onto the
 * operations surface from {@link LegalEntityController}.
 *
 * <p><strong>Deliberately not a full mirror.</strong> {@code
 * LEGAL_ENTITY_MANAGE} is held by {@code TENANT_OWNER} alone among the tenant
 * bundles — neither the administrator nor finance carries it, a stricter
 * single-role gate than the zone or tariff {@code .manage} capabilities need.
 * That existing capability placement already is the judgement this ADR asks
 * for: which company issues a branch's fiscal receipt has tax consequences, so
 * only the tenant's own
 * highest authority may move it, exactly as {@code
 * PAYMENT_MERCHANT_BINDING_MANAGE} is gated. This controller exposes exactly
 * what {@link LegalEntityController} exposes over HTTP — register, list, get,
 * update, activate, suspend, archive, assign, and a location's assignment
 * history — under the same capability, one HTTP method per
 * {@link LegalEntityService} operation on each surface (wave P34 closed the
 * gap: {@code update}, {@code suspend} and {@code archive} used to have no
 * HTTP surface on either controller, so a registered entity could never be
 * corrected and a retired company could only be left {@code ACTIVE}), and no
 * new maker-checker gate is introduced. {@link LegalEntityService#assign}'s
 * mandatory {@code approvalReference} field is already the evidence hook for a
 * decision made through the tenant's general ADR 0027 approval console when
 * one is warranted; inventing a second, endpoint-local approval gate on top of
 * an already single-role-gated action was judged to add process without
 * adding safety.
 *
 * <p>{@code approvedBy} on {@link #assign} is the caller's own authenticated
 * identity, never a request field, for the exact reason {@link
 * LegalEntityController#assign} gives.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/legal-entities")
@Tag(
        name = "Legal entities",
        description = "A tenant's own read of ADR 0038 legal entities and their location assignments")
public class OperationsLegalEntityController {

    private final LegalEntityService legalEntities;
    private final CurrentActor currentActor;

    public OperationsLegalEntityController(LegalEntityService legalEntities, CurrentActor currentActor) {
        this.legalEntities = legalEntities;
        this.currentActor = currentActor;
    }

    @PostMapping
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Register a company inside a tenant",
            description = "Registered in DRAFT. A draft entity cannot be named as a seller until " + "it is activated.")
    ResponseEntity<LegalEntityController.LegalEntityView> register(
            @PathVariable UUID tenantId, @Valid @RequestBody LegalEntityController.RegisterLegalEntityRequest request) {

        LegalEntity entity = legalEntities.register(
                tenantId,
                new RegisterLegalEntityCommand(
                        request.code(),
                        request.legalName(),
                        request.shortName(),
                        request.tin(),
                        request.vatRegistered(),
                        request.vatCertificateReference(),
                        request.taxProfileId(),
                        request.registeredAddress(),
                        request.contactPhone()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{entityId}")
                .buildAndExpand(entity.id().value())
                .toUri();
        return ResponseEntity.created(location).body(LegalEntityController.LegalEntityView.of(entity));
    }

    @GetMapping
    @RequiresCapability(Capability.LEGAL_ENTITY_READ)
    @Operation(summary = "List the tenant's registered legal entities")
    List<LegalEntityController.LegalEntityView> list(@PathVariable UUID tenantId) {
        return legalEntities.list(tenantId).stream()
                .map(LegalEntityController.LegalEntityView::of)
                .toList();
    }

    @GetMapping("/{entityId}")
    @RequiresCapability(Capability.LEGAL_ENTITY_READ)
    @Operation(summary = "Get a legal entity")
    LegalEntityController.LegalEntityView get(@PathVariable UUID tenantId, @PathVariable UUID entityId) {
        return LegalEntityController.LegalEntityView.of(legalEntities.require(tenantId, entityId));
    }

    @PutMapping("/{entityId}")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Correct a registered entity's own fields",
            description = "Everything but the taxpayer number and the lifecycle status. Mirrors "
                    + "LegalEntityController#update exactly.")
    LegalEntityController.LegalEntityView update(
            @PathVariable UUID tenantId,
            @PathVariable UUID entityId,
            @Valid @RequestBody LegalEntityController.UpdateLegalEntityRequest request,
            @RequestParam int expectedVersion) {
        return LegalEntityController.LegalEntityView.of(legalEntities.update(
                tenantId,
                entityId,
                new LegalEntityService.UpdateLegalEntityCommand(
                        request.legalName(),
                        request.shortName(),
                        request.vatRegistered(),
                        request.vatCertificateReference(),
                        request.taxProfileId(),
                        request.registeredAddress(),
                        request.contactPhone()),
                expectedVersion));
    }

    @PostMapping("/{entityId}/activate")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Activate a legal entity",
            description = "Only an ACTIVE entity may be named as a seller. Permitted from DRAFT or SUSPENDED.")
    LegalEntityController.LegalEntityView activate(
            @PathVariable UUID tenantId, @PathVariable UUID entityId, @RequestParam int expectedVersion) {
        return LegalEntityController.LegalEntityView.of(legalEntities.activate(tenantId, entityId, expectedVersion));
    }

    @PostMapping("/{entityId}/suspend")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Suspend a legal entity",
            description = "Its assignments are untouched. Permitted from ACTIVE. Mirrors "
                    + "LegalEntityController#suspend exactly.")
    LegalEntityController.LegalEntityView suspend(
            @PathVariable UUID tenantId, @PathVariable UUID entityId, @RequestParam int expectedVersion) {
        return LegalEntityController.LegalEntityView.of(legalEntities.suspend(tenantId, entityId, expectedVersion));
    }

    @PostMapping("/{entityId}/archive")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Archive a legal entity",
            description = "Permitted from DRAFT or SUSPENDED. The row survives. Mirrors "
                    + "LegalEntityController#archive exactly.")
    LegalEntityController.LegalEntityView archive(
            @PathVariable UUID tenantId, @PathVariable UUID entityId, @RequestParam int expectedVersion) {
        return LegalEntityController.LegalEntityView.of(legalEntities.archive(tenantId, entityId, expectedVersion));
    }

    /**
     * Assigns this entity as a branch's seller from a date.
     *
     * <p>{@code approvedBy} is the caller's own authenticated identity, never a
     * request field: which company sells at a branch is ADR 0027 evidence, and
     * a client-supplied approver name could claim anyone signed it.
     */
    @PostMapping("/{entityId}/assignments")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Assign a legal entity as a location's seller from a date",
            description = "Closes the location's currently open assignment, if any, at the new "
                    + "one's start date. Backdating is permitted; backdating over an existing "
                    + "assignment is refused.")
    ResponseEntity<LegalEntityController.LocationFiscalAssignmentView> assign(
            @PathVariable UUID tenantId,
            @PathVariable UUID entityId,
            @Valid @RequestBody LegalEntityController.AssignLocationRequest request) {

        LocationFiscalAssignment assignment = legalEntities.assign(
                tenantId,
                new AssignLocationCommand(
                        request.brandId(),
                        request.locationId(),
                        entityId,
                        request.effectiveFrom(),
                        currentActor.get().subject(),
                        request.approvalReference()));

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/operations/tenants/{tenantId}/legal-entities/brands/{brandId}"
                        + "/locations/{locationId}/assignments")
                .buildAndExpand(tenantId, request.brandId(), request.locationId())
                .toUri();
        return ResponseEntity.created(location).body(LegalEntityController.LocationFiscalAssignmentView.of(assignment));
    }

    @GetMapping("/brands/{brandId}/locations/{locationId}/assignments")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "A location's fiscal-assignment history, most recent first")
    List<LegalEntityController.LocationFiscalAssignmentView> assignmentHistory(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return legalEntities.assignmentHistory(tenantId, locationId).stream()
                .map(LegalEntityController.LocationFiscalAssignmentView::of)
                .toList();
    }
}
