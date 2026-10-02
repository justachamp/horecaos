package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.tenancy.application.LocationContactPersonService;
import uz.horecaos.platform.tenancy.application.LocationContactPersonService.ContactEdit;
import uz.horecaos.platform.tenancy.application.LocationContactPersonService.ContactSet;
import uz.horecaos.platform.tenancy.application.LocationContactPersonService.ContactView;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A branch's named contact persons (ADR 0139, gap map row {@code 9.2b}), on the
 * location screen.
 *
 * <p>Reads need {@code location.read} and changes {@code location.write}, both at
 * the branch's own {@code LOCATION} scope on a route that names the brand -- the
 * registry's capability, where the T20 note in the gap map wrote
 * {@code LOCATION_MANAGE}. The published line customers and couriers see is
 * {@code tenant.locations.contact_phone} and is not touched here.
 *
 * <p>The set is replaced as a whole. {@code If-Match} carries the <em>location's</em>
 * version, which the replace moves: the contacts are part of the branch.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/contact-persons")
@Tag(name = "Location contact persons", description = "Who to call about a branch (ADR 0139)")
public class LocationContactPersonController {

    private final LocationContactPersonService contacts;
    private final CurrentActor currentActor;

    public LocationContactPersonController(LocationContactPersonService contacts, CurrentActor currentActor) {
        this.contacts = contacts;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's contact persons",
            description = "A colleague is shown by the name and contact phone the tenant keeps for them "
                    + "now; an outside person by the name and phone typed for them. The response carries "
                    + "the location's version, which the next replace must send in If-Match.")
    public ResponseEntity<ContactPersonsResponse> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return respond(contacts.list(tenantId, brandId, locationId));
    }

    @PutMapping
    @RequiresCapability(value = Capability.LOCATION_WRITE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Replace the branch's contact persons (at most ten)",
            description = "Each row is a colleague (`staffMemberId` alone) or an outside person (`name` "
                    + "and `phone`), never both. A colleague of another tenant is refused with the same "
                    + "answer as one that does not exist. Needs If-Match with the location's version.")
    public ResponseEntity<ContactPersonsResponse> replace(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ReplaceContactPersonsRequest body,
            HttpServletRequest request) {
        return respond(contacts.replace(
                tenantId,
                brandId,
                locationId,
                AggregateVersion.requireIfMatch(request),
                body.edits(),
                currentActor.get().subject(),
                body.reason(),
                correlation()));
    }

    private static ResponseEntity<ContactPersonsResponse> respond(ContactSet set) {
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(set.version()))
                .body(new ContactPersonsResponse(
                        set.contacts().stream().map(ContactPersonResponse::of).toList(), set.version()));
    }

    private static String correlation() {
        String fromRequest = MDC.get("correlationId");
        return fromRequest == null || fromRequest.isBlank() ? UUID.randomUUID().toString() : fromRequest;
    }

    public record ContactPersonResponse(
            UUID id,
            String relationshipCode,
            @Nullable UUID staffMemberId,
            @Nullable String staffMemberReference,
            @Classified(DataClass.PERSONAL) @Nullable String name,
            @Classified(DataClass.PERSONAL) @Nullable String phone) {

        static ContactPersonResponse of(ContactView view) {
            return new ContactPersonResponse(
                    view.id(),
                    view.relationshipCode(),
                    view.staffMemberId(),
                    view.staffMemberReference(),
                    view.name(),
                    view.phone());
        }

        @Override
        public String toString() {
            return "ContactPersonResponse[id=" + id + ", relationshipCode=" + relationshipCode + "]";
        }
    }

    public record ContactPersonsResponse(
            // A list is not a record component the scanner descends into; without this the stored
            // reply to a replace (ADR 0031) holds each contact's name and phone in clear (ADR 0029).
            @Classified(DataClass.PERSONAL) List<ContactPersonResponse> contacts, long version) {}

    public record ContactPersonRequest(
            @NotBlank @Size(max = 24) String relationshipCode,
            @Nullable UUID staffMemberId,
            @Size(max = 100) @Nullable String name,
            @Size(max = 40) @Nullable String phone) {

        @Override
        public String toString() {
            return "ContactPersonRequest[relationshipCode=" + relationshipCode + "]";
        }
    }

    public record ReplaceContactPersonsRequest(
            @NotNull @Size(max = 10) List<@Valid ContactPersonRequest> contacts,
            @Size(max = 1000) @Nullable String reason) {

        List<ContactEdit> edits() {
            return contacts.stream()
                    .map(contact -> new ContactEdit(
                            contact.relationshipCode(), contact.staffMemberId(), contact.name(), contact.phone()))
                    .toList();
        }
    }
}
