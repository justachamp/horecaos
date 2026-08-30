package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.customers.application.CustomerPolicyLookup;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.customers.application.CustomerProfileService.RevealedAddress;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcFavouriteStore;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * What a customer may see and change about themselves (ADR 0015, ADR 0029,
 * ADR 0031, ADR 0049).
 *
 * <p>Everything here already existed and was reachable only by staff.
 * {@code CustomerController} carries the same account and the same addresses
 * behind {@code CUSTOMER_MANAGE} and {@code CUSTOMER_PII_REVEAL}, which are ADR
 * 0025 delegated staff authority — an agent acting on somebody else's record. A
 * customer holds no grant row and is not meant to, so those endpoints answered
 * 403 to the person whose data it is, and the storefront's addresses screen had
 * nothing to call. This is the ownership-authorised surface beside them; the
 * staff endpoints are unchanged, because an agent editing an address on the
 * telephone is exactly what a capability is for.
 *
 * <p><strong>The account is never in the path.</strong> {@code /me} resolves it
 * from the caller's own verified token, the way
 * {@code StorefrontOrderingController} does — and here it also closes a gap the
 * storefront had no way around: every account-keyed endpoint takes the id in its
 * path, and nothing published a way for a customer to learn their own.
 *
 * <p>The path carries the tenant and the brand for the reason ADR 0019's
 * storefront paths do: the idempotency interceptor namespaces a key by the path
 * variables naming the resource, and ADR 0015's sketch of {@code
 * /api/v1/customer/me} has nowhere to put a tenant. The brand is load-bearing
 * rather than decorative — under {@code BRAND_ISOLATED} the same person is a
 * different account at each of a tenant's brands, with different addresses, and
 * the brand in the path is what selects between them.
 *
 * <p><strong>A guest gets not-found, deliberately.</strong> A caller with no
 * account at this brand — never signed up, or signed in as a guest — is told the
 * resource does not exist rather than that they are forbidden, because to
 * somebody entitled to nothing those are the same fact and only the second
 * confirms that the brand is real. A caller with no token at all never reaches a
 * handler: these paths are not on the permit list in {@code SecurityConfiguration},
 * so the filter chain answers 401.
 *
 * <p><strong>No endpoint here returns a contact value.</strong> A phone number is
 * ADR 0029 personal data whose decrypt is recorded against a purpose, and putting
 * one on a profile read would decrypt it on every screen paint. The profile says
 * which contact points exist, whether each is verified, and which is primary —
 * facts a settings screen needs — and not the numbers themselves, which the client
 * already knows because its owner typed them. Nothing is masked either, for the
 * reason {@code StorefrontCustomerIdentityController} gives: four unknown digits
 * over a known operator prefix is not anonymity.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/me")
@Tag(
        name = "Customer self-service",
        description = "A customer's own profile, their own saved addresses, and the products they marked")
public class StorefrontCustomerController {

    /**
     * Recorded by the ADR 0027 reveal against every decrypt below.
     *
     * <p>Its own value rather than a borrowed one. {@code DELIVERY_DISPATCH} means
     * a courier was given a doorstep and {@code ORDER_SNAPSHOT} means an order
     * copied one; this means the person the address belongs to asked to see it,
     * which is the one reveal that needs no justifying and must still be
     * distinguishable from the ones that do. A purpose column in which a customer
     * opening their address book is indistinguishable from an export answers
     * nothing that anybody asks it.
     */
    private static final String SELF_SERVICE_PURPOSE = "CUSTOMER_SELF_SERVICE";

    private final CustomerProfileService profiles;
    private final CurrentCustomer currentCustomer;
    private final CustomerPolicyLookup policies;
    private final Clock clock;
    private final JdbcFavouriteStore favourites;

    public StorefrontCustomerController(
            CustomerProfileService profiles,
            CurrentCustomer currentCustomer,
            CustomerPolicyLookup policies,
            Clock clock,
            JdbcFavouriteStore favourites) {
        this.profiles = profiles;
        this.currentCustomer = currentCustomer;
        this.policies = policies;
        this.clock = clock;
        this.favourites = favourites;
    }

    // ------------------------------------------------------------------ profile

    @GetMapping
    @CustomerOwned
    @Operation(
            summary = "The caller's own account and profile",
            description = "Includes the account id, which is otherwise unlearnable from the "
                    + "storefront, and the scope the profile is shared at. Contact points are "
                    + "listed by type and verification state and never by value.")
    public ResponseEntity<ProfileResponse> profile(@PathVariable UUID tenantId, @PathVariable UUID brandId) {

        UUID accountId = accountId(tenantId, brandId);
        JdbcCustomerStore.AccountRow account = profiles.profile(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such account"));

        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(account.version()))
                .body(ProfileResponse.of(
                        account,
                        brandId,
                        policies.policyFor(tenantId, clock.instant()).mode().name(),
                        profiles.contactPointSummaries(tenantId, accountId).stream()
                                .map(ContactPointSummary::of)
                                .toList()));
    }

    @PatchMapping
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Change the caller's own display name, language, or timezone",
            description = "The three fields a customer owns. Status, identity partition, merge "
                    + "target and policy version are not among them: each decides something "
                    + "about the account, and none is the account holder's to set. Under "
                    + "TENANT_SHARED the change is visible at every brand of the tenant, which "
                    + "is what profileScope says.")
    public ResponseEntity<ProfileResponse> updateProfile(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody UpdateProfileRequest body,
            HttpServletRequest request) {

        UUID accountId = accountId(tenantId, brandId);
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
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such account");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
        return profile(tenantId, brandId);
    }

    // ---------------------------------------------------------------- addresses

    @GetMapping("/addresses")
    @CustomerOwned
    @Operation(
            summary = "The caller's own saved addresses",
            description = "Decrypted, because the person they belong to is asking, and the "
                    + "reveal is recorded against a purpose that says so. Archived addresses "
                    + "are not listed.")
    public ResponseEntity<List<AddressResponse>> addresses(@PathVariable UUID tenantId, @PathVariable UUID brandId) {

        UUID accountId = accountId(tenantId, brandId);
        return ResponseEntity.ok(profiles.revealAddresses(tenantId, accountId, SELF_SERVICE_PURPOSE).stream()
                .map(AddressResponse::of)
                .toList());
    }

    @GetMapping("/addresses/{addressId}")
    @CustomerOwned
    @Operation(
            summary = "One of the caller's own saved addresses",
            description = "Not found for an address that is somebody else's, is archived, or "
                    + "never existed. The three are one answer, because telling them apart is "
                    + "how an address id becomes probeable.")
    public ResponseEntity<AddressResponse> address(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID addressId) {

        UUID accountId = accountId(tenantId, brandId);
        RevealedAddress address = profiles.revealAddress(tenantId, accountId, addressId, SELF_SERVICE_PURPOSE)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such address"));

        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(address.version()))
                .body(AddressResponse.of(address));
    }

    @PostMapping("/addresses")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Save a new address",
            description = "The coordinate source is required and must agree with the "
                    + "coordinates: an address awaiting geocoding and one that legitimately has "
                    + "no point — a mahalla house given by its ориентир — are different states, "
                    + "and only one is worth retrying. A customer may claim CUSTOMER_PIN, "
                    + "NOT_GEOCODED or LANDMARK_ONLY; the operator and geocoder sources are not "
                    + "theirs to assert.")
    public ResponseEntity<AddressResponse> addAddress(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SaveAddressRequest body) {

        UUID accountId = accountId(tenantId, brandId);
        CoordinateSource source = customerAsserted(body.coordinateSource());
        UUID addressId;
        try {
            addressId = profiles.addAddress(
                    tenantId,
                    accountId,
                    body.label(),
                    body.fields(),
                    body.deliveryInstructions(),
                    body.latitude(),
                    body.longitude(),
                    source);
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }

        RevealedAddress saved = profiles.revealAddress(tenantId, accountId, addressId, SELF_SERVICE_PURPOSE)
                .orElseThrow(
                        () -> new IllegalStateException("An address just written is not readable by its own account"));

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(saved.version()))
                .body(AddressResponse.of(saved));
    }

    @PutMapping("/addresses/{addressId}")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Replace one of the caller's own addresses",
            description = "The whole address, never a field of it: the lines live inside one "
                    + "encrypted document, and the coordinate and its source have to move "
                    + "together. Requires If-Match, so a second tab loses loudly.")
    public ResponseEntity<AddressResponse> updateAddress(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID addressId,
            @Valid @RequestBody SaveAddressRequest body,
            HttpServletRequest request) {

        UUID accountId = accountId(tenantId, brandId);
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
                    customerAsserted(body.coordinateSource()));
        } catch (CustomerProfileService.AddressNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such address");
        } catch (CustomerProfileService.StaleRecordException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
        return address(tenantId, brandId, addressId);
    }

    @DeleteMapping("/addresses/{addressId}")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Remove one of the caller's own addresses",
            description = "Archived rather than deleted. The row is what a dispute about where "
                    + "an order went is answered from, and nothing depends on it staying "
                    + "readable to the customer: a cart and an order each hold their own copy, "
                    + "taken when the address was chosen, so an order in flight is unaffected. "
                    + "This is not an erasure request, which is a governed act over the whole "
                    + "account.")
    public ResponseEntity<Void> removeAddress(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID addressId,
            HttpServletRequest request) {

        UUID accountId = accountId(tenantId, brandId);
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

    // ----------------------------------------------------------------- favourites

    @GetMapping("/favourites")
    @CustomerOwned
    @Operation(
            summary = "The products this customer marked",
            description = "Product ids, most recently marked first. Ids and not menu items: "
                    + "the storefront already holds the published menu and resolves them "
                    + "against it, which is also what drops a dish this branch has stopped "
                    + "serving instead of showing a card that cannot be ordered.")
    public ResponseEntity<FavouritesResponse> favourites(@PathVariable UUID tenantId, @PathVariable UUID brandId) {

        UUID accountId = accountId(tenantId, brandId);
        return ResponseEntity.ok(new FavouritesResponse(favourites.list(tenantId, brandId, accountId)));
    }

    @PutMapping("/favourites/{productId}")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Mark a product",
            description = "Idempotent: marking twice is one fact, so a double tap or a retried "
                    + "request is a no-op rather than an error. A product that is not this "
                    + "brand's is refused -- a stale menu in a customer's hand is an ordinary "
                    + "way to reach that.")
    public ResponseEntity<Void> addFavourite(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID productId) {

        UUID accountId = accountId(tenantId, brandId);
        if (!favourites.add(tenantId, brandId, accountId, productId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such product on this menu");
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/favourites/{productId}")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Unmark a product",
            description = "204 whether or not it was marked. Removing what was never there is "
                    + "the state the customer asked for.")
    public ResponseEntity<Void> removeFavourite(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID productId) {

        UUID accountId = accountId(tenantId, brandId);
        favourites.remove(tenantId, brandId, accountId, productId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The caller's account, from their own token.
     *
     * <p>Not found rather than forbidden when the principal has no account at this
     * brand, which is the ordinary state of a guest and of anybody who has never
     * ordered here. The two answers are the same fact to a caller entitled to
     * nothing, and only the forbidden one tells somebody probing a brand id that
     * the brand exists.
     */
    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    /**
     * The coordinate sources a customer may claim about their own address.
     *
     * <p>{@code GEOCODER} and {@code OPERATOR_PIN} are assertions about who
     * produced a point, and a customer asserting either would make the column
     * describe something that did not happen — a provenance audit reads it, and a
     * backfill decides whether to re-query on it. {@code LEGACY_UNSOURCED} is
     * refused by the service for the same reason and is listed here so the refusal
     * is a 400 naming the field rather than one naming a constraint.
     */
    private static CoordinateSource customerAsserted(CoordinateSource source) {
        return switch (source) {
            case CUSTOMER_PIN, NOT_GEOCODED, LANDMARK_ONLY -> source;
            case GEOCODER, OPERATOR_PIN, LEGACY_UNSOURCED ->
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "coordinateSource " + source + " records who produced a point and is not a "
                                + "customer's to claim",
                        java.util.Map.of(
                                "field",
                                "coordinateSource",
                                "allowed",
                                List.of(
                                        CoordinateSource.CUSTOMER_PIN.name(),
                                        CoordinateSource.NOT_GEOCODED.name(),
                                        CoordinateSource.LANDMARK_ONLY.name())));
        };
    }

    // ----------------------------------------------------------------- payloads

    /**
     * The caller's own account.
     *
     * @param identityMode  how the tenant partitions customer identity <em>now</em>
     *                      (ADR 0015), from {@code ConfiguredCustomerPolicyLookup}
     * @param profileScope  where a change to this profile actually lands, from the
     *                      account's own partition column. {@code TENANT} means one
     *                      profile across every brand of the tenant;
     *                      {@code BRAND} means this brand's only. The two fields can
     *                      disagree, and when they do this one is right: an account
     *                      is partitioned when it is created and a later governed
     *                      mode change does not retroactively re-partition it
     * @param displayName   what the customer calls themselves, or null if they have
     *                      not said
     */
    public record ProfileResponse(
            UUID accountId,
            UUID brandId,
            String status,
            String identityMode,
            String profileScope,
            Integer identityPolicyVersion,
            /*
             * A customer's own name, and the ADR 0029 name heuristic does not
             * catch it: it looks for "firstName" and "fullName", while
             * "displayName" all over this platform is a brand, a channel, a
             * section or a branch, none of which is personal data. So this one is
             * declared. Without the declaration this response reads as clean, and
             * the idempotency record of a profile edit keeps the customer's name
             * in plain text for a day.
             */
            @Classified(value = DataClass.PERSONAL, reason = "the customer's own name")
            String displayName,

            String preferredLocale,
            String preferredTimezone,
            List<ContactPointSummary> contactPoints,
            int version,
            Instant createdAt) {

        static ProfileResponse of(
                JdbcCustomerStore.AccountRow account,
                UUID brandId,
                String identityMode,
                List<ContactPointSummary> contactPoints) {
            return new ProfileResponse(
                    account.id(),
                    brandId,
                    account.status(),
                    identityMode,
                    account.partitionBrandId() == null ? "TENANT" : "BRAND",
                    account.identityPolicyVersion(),
                    account.displayName(),
                    account.preferredLocale(),
                    account.preferredTimezone(),
                    contactPoints,
                    account.version(),
                    account.createdAt());
        }
    }

    /** A contact point named by kind and state. Never by value — see the class note. */
    public record ContactPointSummary(UUID id, ContactType type, String verificationStatus, boolean primary) {

        static ContactPointSummary of(CustomerProfileService.ContactPointSummary contact) {
            return new ContactPointSummary(
                    contact.id(), contact.type(), contact.verificationStatus(), contact.isPrimary());
        }
    }

    /**
     * @param preferredLocale a BCP 47 tag. Read by the ADR 0020 notification path
     *                        to choose the language a message is sent in, which
     *                        until this endpoint existed nothing ever wrote
     * @param preferredTimezone an IANA zone. Separate from the locale because a
     *                          customer reading Russian in Tashkent is ordinary here
     */
    public record UpdateProfileRequest(
            @Size(max = 200) String displayName,
            @Size(max = 16) String preferredLocale,
            @Size(max = 64) String preferredTimezone) {}

    /**
     * An address as the customer sees their own.
     *
     * @param version the row version, echoed as an ETag, to be sent back as
     *                {@code If-Match} on an edit or a removal
     */
    public record AddressResponse(
            UUID addressId,
            String label,
            AddressFields fields,
            String deliveryInstructions,
            Double latitude,
            Double longitude,
            CoordinateSource coordinateSource,
            int version) {

        static AddressResponse of(RevealedAddress address) {
            return new AddressResponse(
                    address.id(),
                    address.label(),
                    address.fields(),
                    address.deliveryInstructions(),
                    address.latitude(),
                    address.longitude(),
                    address.coordinateSource(),
                    address.version());
        }
    }

    /** @param productIds resolved against the menu by the caller. */
    public record FavouritesResponse(List<UUID> productIds) {}

    /**
     * The whole address, used for both the create and the replace.
     *
     * <p>One record rather than two, because a partial update of an encrypted
     * document is three chances to keep a stale field, and V0021's constraint makes
     * the coordinate and its source a single fact.
     *
     * @param fields including подъезд, этаж and ориентир as their own fields: they
     *               are what actually locate a door here, and a courier cannot use
     *               them buried in a street line
     */
    public record SaveAddressRequest(
            @Size(max = 64) String label,
            @NotNull @Valid AddressFields fields,
            @Size(max = 500) String deliveryInstructions,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull CoordinateSource coordinateSource) {}
}
