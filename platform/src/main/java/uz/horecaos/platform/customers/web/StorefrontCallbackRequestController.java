package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.customers.api.LeadIntake;
import uz.horecaos.platform.customers.api.LeadSource;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * A signed-in guest asks the restaurant to ring her back (ADR 0111 §4: the guest-initiated callback).
 *
 * <p>The request lands as one lead in the call centre's queue, source {@code CALLBACK_REQUEST} (or
 * {@code B2B_CATERING_ENQUIRY} when the guest says it is for a company), linked to her own account.
 * The call back is a reply to her own request, which ADR 0020 classes as transactional and which
 * needs no marketing consent -- the same way an order confirmation needs none.
 *
 * <p><strong>Signed-in only, for now.</strong> The route is authorised by account ownership ({@link
 * CustomerOwned}), like every other thing on {@code /me}: an anonymous form is an unauthenticated
 * write that creates a work item for staff, which needs its own abuse controls (a per-number and a
 * per-caller limit, a verified phone) that ADR 0111 does not decide and that the platform owner has
 * not been asked to. The number the guest wants ringing is in the body, never in a path or a query.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/me/callback-requests")
@Tag(
        name = "Customer self-service",
        description = "A customer's own profile, their own saved addresses, and the products they marked")
public class StorefrontCallbackRequestController {

    private final LeadIntake intake;
    private final CurrentCustomer currentCustomer;

    public StorefrontCallbackRequestController(LeadIntake intake, CurrentCustomer currentCustomer) {
        this.intake = intake;
        this.currentCustomer = currentCustomer;
    }

    @PostMapping
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Ask the restaurant to call me back",
            description = "Creates one lead in the call centre's queue, linked to the caller's own "
                    + "account. 202: what was created is a work item for a person, not a call. The "
                    + "number to ring is in the body. A guest asking on behalf of a company says so "
                    + "with forCompany=true, which files it as a catering enquiry.")
    public ResponseEntity<CallbackRequestResponse> request(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CustomerCallbackRequest body) {
        UUID accountId = currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
        UUID leadId;
        try {
            leadId = intake.registerLead(new LeadIntake.Registration(
                    tenantId,
                    brandId,
                    body.forCompany() != null && body.forCompany()
                            ? LeadSource.B2B_CATERING_ENQUIRY
                            : LeadSource.CALLBACK_REQUEST,
                    body.phone(),
                    null,
                    body.note(),
                    accountId,
                    null,
                    null,
                    "storefront-customer:" + accountId));
        } catch (IllegalArgumentException unusable) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That is not a usable phone number");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CallbackRequestResponse(leadId));
    }

    /**
     * @param phone      the number to ring, as the guest writes it
     * @param note       what she wants to talk about, which is ADR 0029 personal data and is encrypted at rest
     * @param forCompany {@code true} for a company's enquiry; optional and boxed, because the platform's
     *                   JSON reader refuses a missing primitive
     */
    public record CustomerCallbackRequest(
            @NotBlank @Size(max = 32) String phone,

            @Size(max = 1000) @org.jspecify.annotations.Nullable
            String note,

            @org.jspecify.annotations.Nullable Boolean forCompany) {

        /** A record's generated {@code toString} would print the number and the note. */
        @Override
        public String toString() {
            return "CustomerCallbackRequest[forCompany=" + forCompany + "]";
        }
    }

    /** The lead's id, so a later screen of her own could say "we have your request". */
    public record CallbackRequestResponse(@NotNull UUID leadId) {}
}
