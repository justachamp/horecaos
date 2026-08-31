package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import uz.horecaos.platform.tenancy.domain.OperatingUnitStatus;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The companies inside a tenant, and which one sells at a branch (ADR 0038).
 *
 * <p>A thin HTTP adapter over {@link LegalEntityService}. Every decision — a
 * re-registration never rewrites {@code tin}, an assignment closes the branch's
 * open one at the new one's start, activation is required before an entity may
 * be named as a seller — is the service's and the domain's; nothing here
 * duplicates a rule the service already enforces.
 *
 * <p>Every mutation requires {@link Capability#LEGAL_ENTITY_MANAGE}, held only
 * by {@code tenant-owner} among the tenant bundles: which company's name
 * appears on a branch's fiscal receipts is the same class of decision
 * {@code payment.merchant-binding.manage} is, and neither is delegated to the
 * administrator or to finance. Reads use {@link Capability#LEGAL_ENTITY_READ},
 * which the owner, the administrator, and finance all hold.
 *
 * <p>{@code @RequiresCapability(mutating = true)} is what carries ADR 0031's
 * {@code Idempotency-Key} requirement — {@code IdempotencyInterceptor} applies
 * it to every endpoint declaring that flag, so no endpoint here declares the
 * header itself. See {@code TenantControlPlaneController} and
 * {@code GrantController}, which this follows.
 *
 * <p>Listings are plain, unpaginated collections, matching {@code
 * TenantControlPlaneController.getBrands}/{@code getLocations}: a tenant's
 * legal entities and one location's assignment history are both small,
 * bounded, administrative registries — not the kind of table ADR 0031's cursor
 * pagination exists for.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/legal-entities")
@Tag(name = "SaaS control plane", description = "ADR 0038 legal entities and their location assignments")
public class LegalEntityController {

    private final LegalEntityService legalEntities;
    private final CurrentActor currentActor;

    public LegalEntityController(LegalEntityService legalEntities, CurrentActor currentActor) {
        this.legalEntities = legalEntities;
        this.currentActor = currentActor;
    }

    @PostMapping
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Register a company inside a tenant",
            description = "Registered in DRAFT. A draft entity cannot be named as a seller until "
                    + "it is activated, so typing an INN never immediately puts it on a receipt.")
    ResponseEntity<LegalEntityView> register(
            @PathVariable UUID tenantId, @Valid @RequestBody RegisterLegalEntityRequest request) {

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
        return ResponseEntity.created(location).body(LegalEntityView.of(entity));
    }

    @GetMapping
    @RequiresCapability(Capability.LEGAL_ENTITY_READ)
    @Operation(summary = "List the tenant's registered legal entities")
    List<LegalEntityView> list(@PathVariable UUID tenantId) {
        return legalEntities.list(tenantId).stream().map(LegalEntityView::of).toList();
    }

    @GetMapping("/{entityId}")
    @RequiresCapability(Capability.LEGAL_ENTITY_READ)
    @Operation(summary = "Get a legal entity")
    LegalEntityView get(@PathVariable UUID tenantId, @PathVariable UUID entityId) {
        return LegalEntityView.of(legalEntities.require(tenantId, entityId));
    }

    @PostMapping("/{entityId}/activate")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Activate a legal entity",
            description = "Only an ACTIVE entity may be named as a seller. Permitted from DRAFT " + "or SUSPENDED.")
    LegalEntityView activate(
            @PathVariable UUID tenantId, @PathVariable UUID entityId, @RequestParam int expectedVersion) {
        return LegalEntityView.of(legalEntities.activate(tenantId, entityId, expectedVersion));
    }

    /**
     * Assigns this entity as a branch's seller from a date.
     *
     * <p>{@code approvedBy} is the caller's own authenticated identity, never a
     * request field — the same reason {@code GrantController.grant} passes
     * {@code currentActor.get().subject()} beside the command rather than
     * inside it. Which company sells at a branch is ADR 0027 evidence, and a
     * client-supplied approver name could claim anyone signed it.
     */
    @PostMapping("/{entityId}/assignments")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_MANAGE, mutating = true)
    @Operation(
            summary = "Assign a legal entity as a location's seller from a date",
            description = "Closes the location's currently open assignment, if any, at the new "
                    + "one's start date. Backdating is permitted; backdating over an existing "
                    + "assignment is refused.")
    ResponseEntity<LocationFiscalAssignmentView> assign(
            @PathVariable UUID tenantId,
            @PathVariable UUID entityId,
            @Valid @RequestBody AssignLocationRequest request) {

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
                .path("/api/v1/control-plane/tenants/{tenantId}/legal-entities/brands/{brandId}"
                        + "/locations/{locationId}/assignments")
                .buildAndExpand(tenantId, request.brandId(), request.locationId())
                .toUri();
        return ResponseEntity.created(location).body(LocationFiscalAssignmentView.of(assignment));
    }

    @GetMapping("/brands/{brandId}/locations/{locationId}/assignments")
    @RequiresCapability(value = Capability.LEGAL_ENTITY_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "A location's fiscal-assignment history, most recent first")
    List<LocationFiscalAssignmentView> assignmentHistory(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return legalEntities.assignmentHistory(tenantId, locationId).stream()
                .map(LocationFiscalAssignmentView::of)
                .toList();
    }

    record RegisterLegalEntityRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}")
            String code,

            @NotBlank @Size(max = 200) String legalName,
            @Size(max = 200) String shortName,

            @NotBlank @Pattern(regexp = "[0-9]{9}") @Schema(description = "Nine-digit Uzbek INN", example = "123456789")
            String tin,

            boolean vatRegistered,
            @Size(max = 100) String vatCertificateReference,
            UUID taxProfileId,
            @Size(max = 500) String registeredAddress,

            @Size(max = 32) @Pattern(regexp = "\\+[1-9][0-9]{7,14}") @Schema(example = "+998712000000")
            String contactPhone) {}

    record AssignLocationRequest(
            @NotNull UUID brandId,
            @NotNull UUID locationId,
            @NotNull LocalDate effectiveFrom,
            @Size(max = 500) String approvalReference) {}

    record LegalEntityView(
            UUID id,
            String code,
            String legalName,
            @Nullable String shortName,
            String tin,
            boolean vatRegistered,
            @Nullable String vatCertificateReference,
            @Nullable UUID taxProfileId,
            @Nullable String registeredAddress,
            @Nullable String contactPhone,
            OperatingUnitStatus status,
            int version) {

        static LegalEntityView of(LegalEntity entity) {
            return new LegalEntityView(
                    entity.id().value(),
                    entity.code(),
                    entity.legalName(),
                    entity.shortName(),
                    entity.tin().value(),
                    entity.vatRegistered(),
                    entity.vatCertificateReference(),
                    entity.taxProfileId(),
                    entity.registeredAddress(),
                    entity.contactPhone(),
                    entity.status(),
                    entity.version());
        }
    }

    record LocationFiscalAssignmentView(
            UUID id,
            UUID brandId,
            UUID locationId,
            UUID legalEntityId,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveUntil,
            String approvedBy,
            @Nullable String approvalReference,
            int version) {

        static LocationFiscalAssignmentView of(LocationFiscalAssignment assignment) {
            return new LocationFiscalAssignmentView(
                    assignment.id(),
                    assignment.brandId(),
                    assignment.locationId(),
                    assignment.legalEntityId(),
                    assignment.effectiveFrom(),
                    assignment.effectiveUntil(),
                    assignment.approvedBy(),
                    assignment.approvalReference(),
                    assignment.version());
        }
    }
}
