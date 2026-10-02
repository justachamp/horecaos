package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.application.staff.StaffEmergencyContactService;
import uz.horecaos.platform.iam.application.staff.StaffEmergencyContactService.ContactEdit;
import uz.horecaos.platform.iam.application.staff.StaffEmergencyContactService.ContactView;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.EmploymentEdit;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.EndOutcome;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.MemberView;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.ProfileEdit;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.Reach;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The people of a tenant, as the tenant's own record of them (ADR 0139, gap map
 * row {@code 9.2}), and the emergency contacts kept about each (row {@code 9.2b}).
 *
 * <p><strong>Every level has a route of its own, on purpose.</strong> A capability's
 * scope is the scope the route declares, and a grant serves a route only if its
 * own scope is that scope or a broader one (ADR 0025). A branch manager holds a
 * {@code LOCATION} grant, which never covers a {@code TENANT} route, so the same
 * read exists under the tenant, under a brand, and under a branch, each calling
 * one service method with the {@link Reach} its path named. A branch route
 * answers "no such member" for a person with no active job at that branch -- the
 * answer an unknown id gets -- so no level is an existence oracle for another.
 * The {@code brandId} and {@code locationId} filters on the wider routes narrow
 * a result and never widen a scope: the scope of a route is only what its path
 * names.
 *
 * <p>A list carries the masked phone and nothing a scraper could dial; the full
 * number, the employee number and a signed photo link appear only on the
 * single-person response. There is deliberately no export and no phone search
 * (a number in a query string lands in an access log, ADR 0029).
 *
 * <p>Changes carry the expected version in {@code If-Match} and an
 * {@code Idempotency-Key} (ADR 0031). Ending employment is a tenant route only:
 * it also revokes the person's jobs, which needs {@code iam.grant.manage} at
 * each job's scope, and no branch job holds that.
 */
@RestController
@Validated
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Staff members", description = "A tenant's own record of the people who work for it (ADR 0139)")
public class StaffMemberController {

    private final StaffMemberService members;
    private final StaffEmergencyContactService emergencyContacts;
    private final CurrentActor currentActor;

    public StaffMemberController(
            StaffMemberService members, StaffEmergencyContactService emergencyContacts, CurrentActor currentActor) {
        this.members = members;
        this.emergencyContacts = emergencyContacts;
        this.currentActor = currentActor;
    }

    // ============================================================== tenant-wide

    @GetMapping("/staff/members")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's people: name, masked phone, photo flag, employment, languages",
            description = "Tenant-wide, for the owner and the administrator. `brandId` and `locationId` "
                    + "narrow the result to people holding an active job there and never widen it. A "
                    + "person with no active job appears only here. `q` matches a name substring "
                    + "(decrypted in the application) and nothing else; a phone search is not offered "
                    + "because a number in a query string lands in an access log.")
    public Page<StaffMemberResponse> listForTenant(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable @Size(max = 100) String q) {
        return Page.last(members.list(tenantId, Reach.tenant(brandId, locationId), status, q).stream()
                .map(StaffMemberResponse::listing)
                .toList());
    }

    @GetMapping("/staff/members/{memberId}")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One person in full: phone, employee number, signed photo link",
            description = "The full phone appears here and not in the list, with no audit fact, so a "
                    + "manager who needs to call someone is not put behind ceremony; the list's masking "
                    + "removes the bulk-scraping path. Emergency contacts are a separate, audited read.")
    public ResponseEntity<StaffMemberResponse> detailForTenant(
            @PathVariable UUID tenantId, @PathVariable UUID memberId) {
        return versioned(members.detail(tenantId, Reach.tenant(null, null), memberId));
    }

    @PutMapping("/staff/members/{memberId}")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Change a person's profile and employment",
            description = "Replaces the personal and employment fields (a missing optional field clears "
                    + "it). Needs If-Match with the member's version. Status can move between ACTIVE and "
                    + "ON_LEAVE (and reinstate an ENDED member); ending employment is its own act. The "
                    + "sign-in phone and the reset email are Keycloak's and are not editable here.")
    public ResponseEntity<StaffMemberResponse> updateForTenant(
            @PathVariable UUID tenantId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateStaffMemberRequest body,
            HttpServletRequest request) {
        return versioned(members.updateByManager(
                tenantId,
                Reach.tenant(null, null),
                memberId,
                (int) AggregateVersion.requireIfMatch(request),
                body.profile(),
                body.employment(),
                currentActor.get().subject(),
                body.reason(),
                correlation()));
    }

    @PostMapping("/staff/members/{memberId}/end-employment")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "End someone's employment and, in the same act, their access",
            description = "Sets ENDED with an end date, then revokes each of the person's active jobs "
                    + "in this tenant, one audited revoke per job and never as one transaction. The "
                    + "caller must also hold iam.grant.manage at every job's scope (checked before "
                    + "anything changes). The Keycloak account is left alone: it may serve another "
                    + "tenant. If a revoke fails part-way the person stays ENDED with `accessDrift` "
                    + "raised; calling again with the current version revokes what is left.")
    public ResponseEntity<EndEmploymentResponse> endEmployment(
            @PathVariable UUID tenantId,
            @PathVariable UUID memberId,
            @Valid @RequestBody EndEmploymentRequest body,
            HttpServletRequest request) {
        EndOutcome outcome = members.endEmployment(
                tenantId,
                memberId,
                (int) AggregateVersion.requireIfMatch(request),
                body.employedUntil(),
                body.reason(),
                currentActor.get().subject(),
                correlation());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(outcome.member().version()))
                .body(new EndEmploymentResponse(
                        StaffMemberResponse.full(outcome.member()),
                        outcome.revokedGrants(),
                        outcome.remainingGrants()));
    }

    @GetMapping("/staff/members/{memberId}/emergency-contacts")
    @RequiresCapability(value = Capability.STAFF_EMERGENCY_CONTACT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "A person's emergency contacts (audited)",
            description = "A third party's name and phone. Every read writes an audit fact; there is no "
                    + "bulk read. The response carries the member's version for the next replace.")
    public ResponseEntity<EmergencyContactsResponse> emergencyContactsForTenant(
            @PathVariable UUID tenantId, @PathVariable UUID memberId) {
        return contacts(emergencyContacts.read(
                tenantId, Reach.tenant(null, null), memberId, currentActor.get().subject(), correlation()));
    }

    @PutMapping("/staff/members/{memberId}/emergency-contacts")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Replace a person's emergency contacts (at most three)",
            description = "The whole set is replaced. Needs If-Match with the member's version, which the "
                    + "replace moves: the contacts are part of the member.")
    public ResponseEntity<EmergencyContactsResponse> replaceEmergencyContactsForTenant(
            @PathVariable UUID tenantId,
            @PathVariable UUID memberId,
            @Valid @RequestBody ReplaceEmergencyContactsRequest body,
            HttpServletRequest request) {
        return replaced(emergencyContacts.replace(
                tenantId,
                Reach.tenant(null, null),
                memberId,
                (int) AggregateVersion.requireIfMatch(request),
                body.edits(),
                currentActor.get().subject(),
                body.reason(),
                correlation()));
    }

    // ================================================================= one brand

    @GetMapping("/brands/{brandId}/staff/members")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The people holding an active job in this brand or any of its branches",
            description = "For a brand manager. `locationId` narrows to one branch of the brand. A person "
                    + "with a tenant-wide job only, or none, is not in this brand's reach.")
    public Page<StaffMemberResponse> listForBrand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable @Size(max = 100) String q) {
        return Page.last(members.list(tenantId, Reach.brand(brandId, locationId), status, q).stream()
                .map(StaffMemberResponse::listing)
                .toList());
    }

    @GetMapping("/brands/{brandId}/staff/members/{memberId}")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One person of this brand, in full; \"no such member\" for anyone outside its reach")
    public ResponseEntity<StaffMemberResponse> detailForBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID memberId) {
        return versioned(members.detail(tenantId, Reach.brand(brandId, null), memberId));
    }

    // ============================================================== one branch

    @GetMapping("/brands/{brandId}/locations/{locationId}/staff/members")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The people who work at this branch",
            description = "For a branch manager: names, masked phones and employment status of people "
                    + "holding an active job at this very branch, and no one else.")
    public Page<StaffMemberResponse> listForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable @Size(max = 100) String q) {
        return Page.last(members.list(tenantId, Reach.location(brandId, locationId), status, q).stream()
                .map(StaffMemberResponse::listing)
                .toList());
    }

    @GetMapping("/brands/{brandId}/locations/{locationId}/staff/members/{memberId}")
    @RequiresCapability(value = Capability.STAFF_PROFILE_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "One person of this branch, in full; \"no such member\" for anyone with no job here")
    public ResponseEntity<StaffMemberResponse> detailForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID memberId) {
        return versioned(members.detail(tenantId, Reach.location(brandId, locationId), memberId));
    }

    @PutMapping("/brands/{brandId}/locations/{locationId}/staff/members/{memberId}")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Change the profile and employment of a person of this branch",
            description = "Refused unless every active job the person holds is inside this branch: a "
                    + "person who also works elsewhere is not the branch's to change.")
    public ResponseEntity<StaffMemberResponse> updateForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateStaffMemberRequest body,
            HttpServletRequest request) {
        return versioned(members.updateByManager(
                tenantId,
                Reach.location(brandId, locationId),
                memberId,
                (int) AggregateVersion.requireIfMatch(request),
                body.profile(),
                body.employment(),
                currentActor.get().subject(),
                body.reason(),
                correlation()));
    }

    @GetMapping("/brands/{brandId}/locations/{locationId}/staff/members/{memberId}/emergency-contacts")
    @RequiresCapability(value = Capability.STAFF_EMERGENCY_CONTACT_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "A branch person's emergency contacts (audited)")
    public ResponseEntity<EmergencyContactsResponse> emergencyContactsForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID memberId) {
        return contacts(emergencyContacts.read(
                tenantId,
                Reach.location(brandId, locationId),
                memberId,
                currentActor.get().subject(),
                correlation()));
    }

    @PutMapping("/brands/{brandId}/locations/{locationId}/staff/members/{memberId}/emergency-contacts")
    @RequiresCapability(value = Capability.STAFF_PROFILE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Replace a branch person's emergency contacts (at most three)",
            description = "Refused unless every active job the person holds is inside this branch.")
    public ResponseEntity<EmergencyContactsResponse> replaceEmergencyContactsForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID memberId,
            @Valid @RequestBody ReplaceEmergencyContactsRequest body,
            HttpServletRequest request) {
        return replaced(emergencyContacts.replace(
                tenantId,
                Reach.location(brandId, locationId),
                memberId,
                (int) AggregateVersion.requireIfMatch(request),
                body.edits(),
                currentActor.get().subject(),
                body.reason(),
                correlation()));
    }

    // ================================================================= plumbing

    private static ResponseEntity<StaffMemberResponse> versioned(MemberView view) {
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(view.version())).body(StaffMemberResponse.full(view));
    }

    private static ResponseEntity<EmergencyContactsResponse> contacts(StaffEmergencyContactService.Read read) {
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(read.memberVersion()))
                .body(EmergencyContactsResponse.of(read.contacts(), read.memberVersion()));
    }

    private static ResponseEntity<EmergencyContactsResponse> replaced(StaffEmergencyContactService.Replaced replaced) {
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(replaced.memberVersion()))
                .body(EmergencyContactsResponse.of(replaced.contacts(), replaced.memberVersion()));
    }

    static String correlation() {
        String fromRequest = MDC.get("correlationId");
        return fromRequest == null || fromRequest.isBlank() ? UUID.randomUUID().toString() : fromRequest;
    }

    // ================================================================ contracts

    /**
     * A staff member as the console shows them. Optional fields are boxed or
     * nullable throughout: Jackson 3 refuses a missing primitive in a request
     * body, and a response with one invites the matching client.
     *
     * <p>Personal fields are declared {@code PERSONAL} so an idempotent response
     * holding them is stored encrypted (ADR 0029 over ADR 0031), and the
     * generated {@code toString} is overridden so a log line cannot print a person.
     */
    public record StaffMemberResponse(
            UUID memberId,
            String principalSubject,
            String displayReference,
            @Classified(DataClass.PERSONAL) @Nullable String firstName,
            @Classified(DataClass.PERSONAL) @Nullable String lastName,
            @Classified(DataClass.PERSONAL) String displayName,
            @Classified(DataClass.PERSONAL) @Nullable String phone,
            @Classified(DataClass.PERSONAL) @Nullable String maskedPhone,
            @Classified(DataClass.PERSONAL) @Nullable String employeeNumber,
            boolean hasPhoto,
            @Classified(DataClass.PERSONAL) @Nullable URI photoUrl,
            @Nullable String uiLocale,
            List<String> spokenLanguages,
            String employmentStatus,
            @Nullable LocalDate employedFrom,
            @Nullable LocalDate employedUntil,
            boolean hasActiveAccess,
            boolean accessDrift,
            int version,
            Instant updatedAt) {

        static StaffMemberResponse listing(MemberView view) {
            return of(view);
        }

        static StaffMemberResponse full(MemberView view) {
            return of(view);
        }

        private static StaffMemberResponse of(MemberView view) {
            return new StaffMemberResponse(
                    view.memberId(),
                    view.principalSubject(),
                    view.displayReference(),
                    view.firstName(),
                    view.lastName(),
                    view.displayName(),
                    view.phone(),
                    view.maskedPhone(),
                    view.employeeNumber(),
                    view.hasPhoto(),
                    view.photoUrl(),
                    view.uiLocale(),
                    view.spokenLanguages(),
                    view.employmentStatus(),
                    view.employedFrom(),
                    view.employedUntil(),
                    view.hasActiveAccess(),
                    view.accessDrift(),
                    view.version(),
                    view.updatedAt());
        }

        @Override
        public String toString() {
            return "StaffMemberResponse[displayReference=" + displayReference + ", status=" + employmentStatus
                    + ", version=" + version + "]";
        }
    }

    public record EndEmploymentResponse(StaffMemberResponse member, int revokedGrants, int remainingGrants) {}

    public record UpdateStaffMemberRequest(
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) @Nullable String lastName,
            @Size(max = 40) @Nullable String phone,
            @Size(max = 8) @Nullable String uiLocale,
            @Nullable List<@Size(max = 8) String> spokenLanguages,
            @Size(max = 16) @Nullable String employmentStatus,
            @Size(max = 32) @Nullable String employeeNumber,
            @Nullable LocalDate employedFrom,
            @Nullable LocalDate employedUntil,
            @Size(max = 1000) @Nullable String reason) {

        ProfileEdit profile() {
            return new ProfileEdit(firstName, lastName, phone, uiLocale, spokenLanguages);
        }

        EmploymentEdit employment() {
            return new EmploymentEdit(employmentStatus, employeeNumber, employedFrom, employedUntil);
        }

        @Override
        public String toString() {
            return "UpdateStaffMemberRequest[<redacted>]";
        }
    }

    public record EndEmploymentRequest(
            @NotBlank @Size(max = 1000) String reason,
            @Nullable LocalDate employedUntil) {}

    public record EmergencyContactResponse(
            UUID id,
            String relationshipCode,
            @Classified(DataClass.PERSONAL) String name,
            @Classified(DataClass.PERSONAL) String phone,
            int slot) {

        @Override
        public String toString() {
            return "EmergencyContactResponse[id=" + id + ", relationshipCode=" + relationshipCode + ", slot=" + slot
                    + "]";
        }
    }

    public record EmergencyContactsResponse(
            // The scanner reads record components and a list is not one: without this the stored
            // reply to a replace (ADR 0031) holds a third party's name in clear (ADR 0029).
            @Classified(DataClass.PERSONAL) List<EmergencyContactResponse> contacts, int memberVersion) {

        static EmergencyContactsResponse of(List<ContactView> views, int memberVersion) {
            return new EmergencyContactsResponse(
                    views.stream()
                            .map(view -> new EmergencyContactResponse(
                                    view.id(), view.relationshipCode(), view.name(), view.phone(), view.slot()))
                            .toList(),
                    memberVersion);
        }
    }

    public record EmergencyContactRequest(
            @NotBlank @Size(max = 24) String relationshipCode,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 40) String phone) {

        @Override
        public String toString() {
            return "EmergencyContactRequest[relationshipCode=" + relationshipCode + "]";
        }
    }

    public record ReplaceEmergencyContactsRequest(
            @NotNull @Size(max = 3) List<@Valid EmergencyContactRequest> contacts,
            @Size(max = 1000) @Nullable String reason) {

        List<ContactEdit> edits() {
            return contacts.stream()
                    .map(contact -> new ContactEdit(contact.relationshipCode(), contact.name(), contact.phone()))
                    .toList();
        }
    }
}
