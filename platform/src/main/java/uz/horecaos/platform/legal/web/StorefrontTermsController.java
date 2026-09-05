package uz.horecaos.platform.legal.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.legal.application.TermsAcceptanceService;
import uz.horecaos.platform.legal.application.TermsAcceptanceService.AcceptanceRecord;
import uz.horecaos.platform.legal.application.TermsAcceptanceService.AcceptanceStatus;
import uz.horecaos.platform.legal.domain.EffectiveTerms;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The terms of service a storefront customer reads and accepts (ADR 0067).
 *
 * <p>The read is unauthenticated, like the menu and {@code
 * StorefrontSupportController}'s FAQ: somebody deciding whether to sign up
 * reads this before they have an account, and the storefront's own sign-in
 * screen links here for exactly that reason. Accepting and checking
 * acceptance status require a session — there is nobody to accept on behalf
 * of otherwise — and are authorised by {@link CustomerOwned}, the same
 * ownership check {@code StorefrontCustomerController} uses, because a
 * customer accepting their own terms holds no ADR 0025 grant to declare.
 *
 * <p>{@code brandName} travels as a query parameter / body field rather than
 * being looked up here, so this module takes no dependency on {@code
 * tenancy.api} for a single display string: the storefront already has it in
 * {@code AppConfig.brand.displayName}, loaded at bootstrap for exactly this
 * kind of interpolation (see {@code applyBrand.ts}). It is used only to
 * render the platform-default document a tenant has not overridden and is
 * never persisted, so a caller passing an unexpected value only ever
 * mis-renders its own read — it cannot corrupt evidence.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/terms")
@Tag(
        name = "Storefront terms of service",
        description = "The tenant's own document, or the platform default (ADR 0067)")
public class StorefrontTermsController {

    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final TermsAcceptanceService acceptance;
    private final CurrentCustomer currentCustomer;

    public StorefrontTermsController(TermsAcceptanceService acceptance, CurrentCustomer currentCustomer) {
        this.acceptance = acceptance;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping
    @Operation(
            summary = "The document in force right now, in the requested language",
            description = "The tenant's own published text when it has one for this language; "
                    + "otherwise the platform's brand-neutral default with brandName interpolated "
                    + "in. Cached briefly like the FAQ, because this changes on the order of weeks.")
    ResponseEntity<TermsView> current(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String locale,
            @RequestParam String brandName) {

        EffectiveTerms effective = acceptance.effective(tenantId, brandId, locale, requireBrandName(brandName));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(TermsView.of(effective));
    }

    @PostMapping("/accept")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Record that the signed-in customer accepts the document currently in force",
            description = "Always accepts whatever GET would answer right now for this locale — a "
                    + "client cannot name an older or newer version. Recorded through the same ADR "
                    + "0015 consent store every other acceptance in the platform uses, so publishing "
                    + "a later version never rewrites what this call recorded.")
    ResponseEntity<AcceptResponse> accept(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody AcceptRequest request) {

        UUID accountId = accountId(tenantId, brandId);
        AcceptanceRecord recorded = acceptance.accept(
                tenantId, brandId, accountId, request.locale(), requireBrandName(request.brandName()));
        return ResponseEntity.ok(new AcceptResponse(recorded.policyVersionLabel(), recorded.acceptedAt()));
    }

    @GetMapping("/acceptance-status")
    @CustomerOwned
    @Operation(
            summary = "Whether the signed-in customer has accepted the version currently in force",
            description = "False for a customer who never accepted anything, and false again the day "
                    + "after a new version publishes for a customer who only ever accepted the one "
                    + "before it — the storefront reads this after sign-in to decide whether to ask.")
    AcceptanceStatusView acceptanceStatus(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String locale,
            @RequestParam String brandName) {

        UUID accountId = accountId(tenantId, brandId);
        AcceptanceStatus status = acceptance.status(tenantId, brandId, accountId, locale, requireBrandName(brandName));
        return AcceptanceStatusView.of(status);
    }

    /** See {@code StorefrontCustomerController.accountId}: not-found rather than forbidden for a guest at this brand. */
    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    private static String requireBrandName(@Nullable String brandName) {
        if (brandName == null || brandName.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "brandName is required");
        }
        return brandName;
    }

    record TermsView(
            String locale,
            boolean isPlatformDefault,
            @Nullable Integer version,
            String body) {
        static TermsView of(EffectiveTerms effective) {
            return new TermsView(
                    effective.locale(), effective.isPlatformDefault(), effective.documentVersion(), effective.body());
        }
    }

    record AcceptRequest(@NotBlank String locale, @NotBlank String brandName) {}

    record AcceptResponse(String version, Instant acceptedAt) {}

    record AcceptanceStatusView(
            boolean accepted,
            String currentVersion,
            @Nullable String lastAcceptedVersion,
            @Nullable Instant lastAcceptedAt) {
        static AcceptanceStatusView of(AcceptanceStatus status) {
            return new AcceptanceStatusView(
                    status.accepted(),
                    status.currentVersionLabel(),
                    status.lastAcceptedLabel(),
                    status.lastAcceptedAt());
        }
    }
}
