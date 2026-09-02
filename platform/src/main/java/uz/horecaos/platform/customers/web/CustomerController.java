package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.CustomerBlacklistService;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerListQueryService;
import uz.horecaos.platform.customers.application.CustomerListQueryService.HeaderCounts;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Customer accounts, personal data, and consent (ADR 0015, ADR 0029).
 *
 * <p>Revealing a decrypted contact detail needs a capability of its own and a
 * stated purpose. Seeing that a customer exists and reading their phone number
 * are different levels of access, and the difference between an agent viewing one
 * customer and exporting fifty thousand is exactly what the purpose captures.
 *
 * <p>The grid, the header counters, the filtered export, manual creation, the
 * date of birth, the blacklist, and identity merge (frontend information
 * architecture §5.1-5.2) all land here, on the same tenant-scoped surface as
 * the reveal endpoints they share a capability model with. Order history lives
 * in the ordering module instead — see {@code CustomerOrderHistoryController}'s
 * own doc for why splitting it out there is the right seam and not scope creep.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/customers")
@Tag(name = "Customers", description = "Customer accounts, contact details, addresses, and consent")
public class CustomerController {

    private final CustomerIdentityService identity;
    private final CustomerProfileService profiles;
    private final ConsentService consent;
    private final CustomerListQueryService lists;
    private final CustomerBlacklistService blacklist;
    private final CurrentActor currentActor;
    private final String trustedIssuer;

    public CustomerController(
            CustomerIdentityService identity,
            CustomerProfileService profiles,
            ConsentService consent,
            CustomerListQueryService lists,
            CustomerBlacklistService blacklist,
            CurrentActor currentActor,
            @org.springframework.beans.factory.annotation.Value(
                            "${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
                    String trustedIssuer) {
        this.identity = identity;
        this.profiles = profiles;
        this.consent = consent;
        this.lists = lists;
        this.blacklist = blacklist;
        this.currentActor = currentActor;
        this.trustedIssuer = trustedIssuer;
    }

    private ActorRef staffActor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    @PostMapping("/resolve")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Resolve the signed-in principal to a durable account",
            description = "Creates one on first sign-in. The identity comes from the caller's own "
                    + "token; there is no way to resolve an account for someone else. Resolution "
                    + "is on issuer and subject only, because a phone number is a contact method "
                    + "and not an identity key.")
    public ResponseEntity<ResolveResponse> resolve(
            @PathVariable UUID tenantId, @Valid @RequestBody ResolveRequest request) {

        // Subject and issuer come from the verified token, never from the body.
        // Taking them from the request would let any caller holding this
        // capability create or claim an account for an arbitrary identity, which
        // is impersonation with extra steps. A support agent acting for a
        // customer needs its own endpoint that records both identities.
        var resolution = identity.resolve(
                tenantId, request.brandId(), trustedIssuer, currentActor.get().subject());

        return ResponseEntity.ok(new ResolveResponse(
                resolution.account().accountId(),
                resolution.created(),
                resolution.policy().name()));
    }

    @PostMapping("/{accountId}/contact-points")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Add a phone number or email address",
            description = "Accepts a value another account already holds. Refusing would be wrong: "
                    + "households share numbers, and merging on a match is what ADR 0015 forbids.")
    public ResponseEntity<IdResponse> addContact(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @Valid @RequestBody AddContactRequest request) {
        try {
            return ResponseEntity.ok(new IdResponse(
                    profiles.addContactPoint(tenantId, accountId, request.type(), request.value(), request.primary())));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    @GetMapping("/{accountId}/contact-points")
    @RequiresCapability(Capability.CUSTOMER_PII_REVEAL)
    @Operation(
            summary = "Reveal decrypted contact details",
            description = "Requires a stated purpose, recorded as an audit fact.")
    public ResponseEntity<List<CustomerProfileService.RevealedContact>> contacts(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @RequestParam @NotBlank String purpose) {
        return ResponseEntity.ok(profiles.revealContactPoints(
                tenantId, accountId, purpose, ActorRef.user(currentActor.get().subject(), null)));
    }

    @PostMapping("/{accountId}/addresses")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Add a delivery address",
            description = "Address lines — including подъезд, этаж and ориентир — are encrypted; "
                    + "coordinates are not, because a courier cannot be routed to a ciphertext "
                    + "and a coordinate identifies a building. The coordinate source is required: "
                    + "an address awaiting geocoding and one that legitimately has no point are "
                    + "different states, and only one of them is worth retrying.")
    public ResponseEntity<IdResponse> addAddress(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @Valid @RequestBody AddAddressRequest request) {
        try {
            return ResponseEntity.ok(new IdResponse(profiles.addAddress(
                    tenantId,
                    accountId,
                    request.label(),
                    request.fields(),
                    request.deliveryInstructions(),
                    request.latitude(),
                    request.longitude(),
                    request.coordinateSource())));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    @GetMapping("/{accountId}/addresses")
    @RequiresCapability(Capability.CUSTOMER_PII_REVEAL)
    @Operation(summary = "Reveal decrypted addresses")
    public ResponseEntity<List<CustomerProfileService.RevealedAddress>> addresses(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @RequestParam @NotBlank String purpose) {
        return ResponseEntity.ok(profiles.revealAddresses(
                tenantId, accountId, purpose, ActorRef.user(currentActor.get().subject(), null)));
    }

    @PostMapping("/{accountId}/consent-decisions")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Record a consent decision",
            description = "Append-only. A withdrawal supersedes a grant without erasing it, "
                    + "because proving what someone agreed to and when is the obligation.")
    public ResponseEntity<IdResponse> recordConsent(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @Valid @RequestBody ConsentRequest request) {
        return ResponseEntity.ok(new IdResponse(consent.record(
                tenantId,
                accountId,
                request.brandId(),
                request.purpose(),
                request.channel(),
                request.decision(),
                request.policyVersion(),
                request.source(),
                request.evidenceReference(),
                request.decidedAt())));
    }

    @GetMapping("/{accountId}/consent-decisions")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(summary = "The full consent history", description = "What a subject-access request produces.")
    public ResponseEntity<List<?>> consentHistory(@PathVariable UUID tenantId, @PathVariable UUID accountId) {
        return ResponseEntity.ok(consent.history(tenantId, accountId));
    }

    // -------------------------------------------------------------- §5.1 the grid

    @GetMapping
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "The tenant's customer accounts, newest first",
            description = "Cursor-paginated per ADR 0031. No contact value and no address on this "
                    + "list, ever — that is the 'never a list-wide decrypt' rule frontend "
                    + "information architecture §5.1 is built around. `query` matches a phone "
                    + "number by its keyed hash when it normalizes to one, and the customer's "
                    + "display name otherwise.")
    public Page<CustomerSummaryResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @Schema(description = "The nextCursor of the previous page") UUID cursor,
            @RequestParam(required = false) Integer limit) {

        requireKnownStatus(status);
        int pageSize = Page.limitOrDefault(limit);
        List<JdbcCustomerStore.AccountSummaryRow> rows;
        try {
            rows = lists.list(tenantId, status, query, cursor, pageSize);
        } catch (CustomerListQueryService.UnknownCursorException unusable) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, unusable.getMessage());
        }

        String nextCursor =
                rows.size() < pageSize ? null : rows.get(rows.size() - 1).id().toString();
        return new Page<>(rows.stream().map(CustomerSummaryResponse::of).toList(), nextCursor);
    }

    /**
     * Refuses an unknown status by name rather than silently returning an
     * empty page, mirroring {@code OperationsOrderController#requireKnownStatus}.
     * {@code MERGED} is deliberately not accepted: {@code
     * CustomerListQueryService#list} excludes it unconditionally, so filtering
     * to it would always answer empty, which reads as a bug rather than the
     * status this grid does not show duplicates under.
     */
    private static void requireKnownStatus(@Nullable String status) {
        if (status == null) {
            return;
        }
        if (!List.of("ACTIVE", "SUSPENDED", "ANONYMIZED", "CLOSED").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown customer status \"%s\"".formatted(status));
        }
    }

    @GetMapping("/counts")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "The grid header's three counters",
            description = "Total, registered today, and ordered today — computed for UTC midnight "
                    + "(CustomerListQueryService#counts explains why). 'Ordered today' asks the "
                    + "ordering module's own OrderDirectory rather than this module's own tables.")
    public ResponseEntity<CountsResponse> counts(@PathVariable UUID tenantId) {
        HeaderCounts counts = lists.counts(tenantId);
        return ResponseEntity.ok(new CountsResponse(counts.total(), counts.registeredToday(), counts.orderedToday()));
    }

    @GetMapping("/export")
    @RequiresCapability(Capability.CUSTOMER_PII_REVEAL)
    @Operation(
            summary = "A filtered export, decrypted",
            description = "One audited PII egress event for the whole filtered set (frontend "
                    + "information architecture §5.1), never one reveal per row. Requires a "
                    + "stated purpose, exactly like every other reveal on this controller. Bounded "
                    + "at 2000 rows.")
    public ResponseEntity<List<CustomerExportResponse>> export(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam @NotBlank String purpose) {
        requireKnownStatus(status);
        return ResponseEntity.ok(lists.exportFiltered(tenantId, status, query, purpose, staffActor()).stream()
                .map(CustomerExportResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Create a customer by hand",
            description = "The staff-initiated counterpart to storefront sign-in: an operator "
                    + "taking a phone call opens an account with no Keycloak principal link, the "
                    + "same channel-identity-only shape ADR 0059's SendPulse import uses. It "
                    + "starts non-contactable — no consent decision exists for it, and 'absence "
                    + "of a decision is not consent' already handles that (ConsentService's own "
                    + "doc) — so no origin column is needed to say so.")
    public ResponseEntity<IdResponse> createManually(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateCustomerRequest request) {
        try {
            var account = identity.createAccountWithoutPrincipal(tenantId, request.brandId());
            profiles.addContactPoint(tenantId, account.accountId(), ContactType.PHONE, request.phone(), true);
            if (request.displayName() != null && !request.displayName().isBlank()) {
                // A brand-new account from insertAccount's own DEFAULT 1.
                profiles.updateProfile(tenantId, account.accountId(), 1, request.displayName(), null, null);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(account.accountId()));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    // ------------------------------------------------------------- §5.2 the detail

    @GetMapping("/{accountId}")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "One customer's profile",
            description = "Display name, status, and preferences — never a contact value or an "
                    + "address, which stay behind their own reveal-gated endpoints. "
                    + "`hasDateOfBirth` says whether one is on file without decrypting it.")
    public ResponseEntity<CustomerProfileResponse> profile(@PathVariable UUID tenantId, @PathVariable UUID accountId) {
        var account = profiles.profile(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer"));
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(account.version()))
                .body(CustomerProfileResponse.of(account, profiles.contactPointSummaries(tenantId, accountId)));
    }

    @PutMapping("/{accountId}/profile")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Change the display name, language, or timezone",
            description = "Requires If-Match, so a second tab loses loudly.")
    public ResponseEntity<CustomerProfileResponse> updateProfile(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateProfileRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            profiles.updateProfile(
                    tenantId,
                    accountId,
                    (int) expected,
                    body.displayName(),
                    body.preferredLocale(),
                    body.preferredTimezone());
        } catch (CustomerProfileService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
        return profile(tenantId, accountId);
    }

    @PutMapping("/{accountId}/date-of-birth")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Set or clear the date of birth",
            description = "Staff-only, unlike the three self-service profile fields — "
                    + "CustomerProfileService#updateDateOfBirth explains why. A null "
                    + "`dateOfBirth` clears a value already on file. Requires If-Match.")
    public ResponseEntity<Void> setDateOfBirth(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @Valid @RequestBody DateOfBirthRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        if (body.dateOfBirth() != null) {
            try {
                LocalDate.parse(body.dateOfBirth());
            } catch (DateTimeParseException malformed) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "dateOfBirth must be an ISO-8601 date (yyyy-MM-dd)");
            }
        }
        try {
            profiles.updateDateOfBirth(tenantId, accountId, (int) expected, body.dateOfBirth());
        } catch (CustomerProfileService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{accountId}/date-of-birth")
    @RequiresCapability(Capability.CUSTOMER_PII_REVEAL)
    @Operation(
            summary = "Reveal the date of birth",
            description = "Requires a stated purpose, recorded as an audit fact.")
    public ResponseEntity<DateOfBirthResponse> revealDateOfBirth(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @RequestParam @NotBlank String purpose) {
        try {
            return ResponseEntity.ok(
                    new DateOfBirthResponse(profiles.revealDateOfBirth(tenantId, accountId, purpose, staffActor())
                            .orElse(null)));
        } catch (CustomerProfileService.AccountNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        }
    }

    @PutMapping("/{accountId}/addresses/{addressId}")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Replace one of this customer's addresses",
            description = "Operator-visible, operator-editable (frontend information architecture "
                    + "§5.2) — the same write StorefrontCustomerController exposes to the "
                    + "customer themselves, here gated on CUSTOMER_MANAGE instead of account "
                    + "ownership. Requires If-Match.")
    public ResponseEntity<CustomerProfileService.RevealedAddress> updateAddress(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @PathVariable UUID addressId,
            @Valid @RequestBody AddAddressRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            profiles.updateAddress(
                    tenantId,
                    accountId,
                    addressId,
                    (int) expected,
                    body.label(),
                    body.fields(),
                    body.deliveryInstructions(),
                    body.latitude(),
                    body.longitude(),
                    body.coordinateSource());
        } catch (CustomerProfileService.AddressNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such address");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
        return ResponseEntity.ok(profiles.revealAddress(
                        tenantId, accountId, addressId, "Operations console: view the delivery address", staffActor())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such address")));
    }

    @DeleteMapping("/{accountId}/addresses/{addressId}")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Archive one of this customer's addresses",
            description = "Archived, never deleted. Requires If-Match.")
    public ResponseEntity<Void> archiveAddress(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @PathVariable UUID addressId,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            profiles.archiveAddress(tenantId, accountId, addressId, (int) expected);
        } catch (CustomerProfileService.AddressNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such address");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- blacklist

    @GetMapping("/{accountId}/blacklist-status")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "Whether this customer is blacklisted right now, with no reveal",
            description = "No reason, no reveal, no audit fact — mirrors CustomerProfileService's "
                    + "own existence-without-reveal split for contact points. Enough for the "
                    + "detail pane's badge; GET .../blacklist-entries is the reveal.")
    public ResponseEntity<BlacklistStatusResponse> blacklistStatus(
            @PathVariable UUID tenantId, @PathVariable UUID accountId) {
        var status = blacklist.status(tenantId, accountId);
        return ResponseEntity.ok(
                new BlacklistStatusResponse(status.active(), status.expired(), status.expiresAt(), status.since()));
    }

    @GetMapping("/{accountId}/blacklist-entries")
    @RequiresCapability(Capability.CUSTOMER_PII_REVEAL)
    @Operation(
            summary = "The decrypted blacklist history",
            description = "Every entry, reason and lift reason revealed together, in one audit "
                    + "fact. Requires a stated purpose.")
    public ResponseEntity<List<CustomerBlacklistService.RevealedEntry>> blacklistHistory(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @RequestParam @NotBlank String purpose) {
        return ResponseEntity.ok(blacklist.revealHistory(tenantId, accountId, purpose, staffActor()));
    }

    @PostMapping("/{accountId}/blacklist-entries")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Blacklist this customer",
            description = "Reason, actor, and an optional expiry (frontend information "
                    + "architecture §5.2). Refused while an entry is already active — lift it "
                    + "first. The enforcement point is CustomerIdentityService#resolve; see its "
                    + "own doc.")
    public ResponseEntity<IdResponse> addBlacklistEntry(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @Valid @RequestBody AddBlacklistRequest body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new IdResponse(
                            blacklist.add(tenantId, accountId, body.reason(), body.expiresAt(), staffActor())));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        } catch (CustomerBlacklistService.AlreadyBlacklistedException already) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, already.getMessage());
        }
    }

    @PostMapping("/{accountId}/blacklist-entries/lift")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(summary = "Lift the active blacklist entry")
    public ResponseEntity<Void> liftBlacklistEntry(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @RequestBody LiftBlacklistRequest body) {
        try {
            blacklist.lift(tenantId, accountId, body.reason(), staffActor());
        } catch (CustomerBlacklistService.NoActiveEntryException none) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, none.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------------- merge

    @PostMapping("/{accountId}/merge")
    @RequiresCapability(value = Capability.CUSTOMER_MANAGE, mutating = true)
    @Operation(
            summary = "Merge this account into another",
            description = "Identity merge for aggregator-masked identities (frontend information "
                    + "architecture §5.2): `accountId` becomes a redirect to `targetAccountId`, "
                    + "never the reverse — see CustomerIdentityService#merge for what this does "
                    + "and does not do. Requires If-Match against `accountId`'s own version.")
    public ResponseEntity<Void> merge(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @Valid @RequestBody MergeRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            identity.merge(tenantId, accountId, body.targetAccountId(), (int) expected, staffActor());
        } catch (CustomerIdentityService.SelfMergeException
                | CustomerIdentityService.MergeTargetInvalidException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        } catch (CustomerIdentityService.MergeConflictException conflict) {
            throw ApiException.staleVersion(conflict.expected(), conflict.actual());
        }
        return ResponseEntity.noContent().build();
    }

    /** Only the brand. The identity is taken from the caller's token. */
    public record ResolveRequest(@NotNull UUID brandId) {}

    public record ResolveResponse(UUID accountId, boolean created, String identityPolicy) {}

    public record AddContactRequest(
            @NotNull ContactType type,
            @NotBlank @Size(max = 320) String value,
            boolean primary) {}

    /**
     * A new address to add to the customer's book.
     *
     * @param coordinateSource required rather than inferred from whether
     *                         coordinates arrived, because inferring it would
     *                         make "not geocoded yet" and "this address has no
     *                         point" the same request
     */
    public record AddAddressRequest(
            @Size(max = 64) String label,
            @NotNull AddressFields fields,
            @Size(max = 500) @Nullable String deliveryInstructions,
            @DecimalMin("-90") @DecimalMax("90") @Nullable Double latitude,
            @DecimalMin("-180") @DecimalMax("180") @Nullable Double longitude,
            @NotNull CoordinateSource coordinateSource) {}

    public record ConsentRequest(
            @Nullable UUID brandId,
            @NotBlank String purpose,
            @Nullable String channel,
            @NotNull ConsentService.Decision decision,
            @NotBlank String policyVersion,
            @NotNull ConsentService.Source source,
            @Nullable String evidenceReference,
            @Nullable Instant decidedAt) {}

    public record IdResponse(UUID id) {}

    /** One row of the grid — no contact value, no address. See {@link #list}'s own doc. */
    public record CustomerSummaryResponse(
            UUID id, String status, @Nullable String displayName, Instant createdAt) {
        static CustomerSummaryResponse of(JdbcCustomerStore.AccountSummaryRow row) {
            return new CustomerSummaryResponse(row.id(), row.status(), row.displayName(), row.createdAt());
        }
    }

    /** {@code GET .../customers/counts}: the grid header's three counters. */
    public record CountsResponse(long total, long registeredToday, long orderedToday) {}

    /** One decrypted row of a filtered export — see {@link #export}'s own doc on the single audit fact behind the whole call. */
    public record CustomerExportResponse(
            UUID accountId,
            String status,
            @Nullable String displayName,
            @Nullable String phone) {
        static CustomerExportResponse of(CustomerListQueryService.ExportRow row) {
            return new CustomerExportResponse(row.accountId(), row.status(), row.displayName(), row.phone());
        }
    }

    /** A manually created customer. {@code brandId} decides the identity partition, same as {@code resolve}. */
    public record CreateCustomerRequest(
            @NotNull UUID brandId,
            @NotBlank @Size(max = 32) String phone,
            @Size(max = 200) @Nullable String displayName) {}

    /** One customer's profile, as {@link #profile} and {@link #updateProfile} render it. */
    public record CustomerProfileResponse(
            UUID id,
            String status,
            @Nullable String displayName,
            @Nullable String preferredLocale,
            @Nullable String preferredTimezone,
            Instant createdAt,
            int version,
            boolean hasDateOfBirth,
            List<ContactSummaryResponse> contactSummaries) {
        static CustomerProfileResponse of(
                JdbcCustomerStore.AccountRow account, List<CustomerProfileService.ContactPointSummary> contacts) {
            return new CustomerProfileResponse(
                    account.id(),
                    account.status(),
                    account.displayName(),
                    account.preferredLocale(),
                    account.preferredTimezone(),
                    account.createdAt(),
                    account.version(),
                    account.dateOfBirthEncrypted() != null,
                    contacts.stream().map(ContactSummaryResponse::of).toList());
        }
    }

    /** A contact point named by kind and state, with no value and therefore no decrypt. */
    public record ContactSummaryResponse(UUID id, String type, String verificationStatus, boolean isPrimary) {
        static ContactSummaryResponse of(CustomerProfileService.ContactPointSummary summary) {
            return new ContactSummaryResponse(
                    summary.id(), summary.type().name(), summary.verificationStatus(), summary.isPrimary());
        }
    }

    public record UpdateProfileRequest(
            @Size(max = 200) @Nullable String displayName,
            @Size(max = 16) @Nullable String preferredLocale,
            @Size(max = 64) @Nullable String preferredTimezone) {}

    /** @param dateOfBirth ISO-8601 {@code yyyy-MM-dd}, or null to clear a value already on file */
    public record DateOfBirthRequest(@Nullable String dateOfBirth) {}

    public record DateOfBirthResponse(@Nullable String dateOfBirth) {}

    public record BlacklistStatusResponse(
            boolean active,
            boolean expired,
            @Nullable Instant expiresAt,
            @Nullable Instant since) {}

    public record AddBlacklistRequest(
            @NotBlank @Size(max = 2000) String reason,
            @Nullable Instant expiresAt) {}

    public record LiftBlacklistRequest(@Nullable String reason) {}

    public record MergeRequest(@NotNull UUID targetAccountId) {}
}
