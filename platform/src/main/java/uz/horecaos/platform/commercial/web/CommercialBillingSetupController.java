package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.application.PlatformBillingSettingsService;
import uz.horecaos.platform.commercial.application.PlatformCardInstallationService;
import uz.horecaos.platform.commercial.application.WalletService.WalletChangeOutcome;
import uz.horecaos.platform.commercial.domain.PlatformBillingSettings;
import uz.horecaos.platform.commercial.domain.PlatformCardInstallation;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What HorecaOS itself sets up so it can be paid (ADR 0095): the bank details an invoice carries, and the
 * card merchant account it charges through.
 *
 * <p>Both are HorecaOS staff's alone. The bank details are the one thing an invoice says that a tenant
 * acts on without checking, so changing them is proposed by one person and approved by a different one,
 * exactly like a refund; the card account is an installation (ADR 0026) whose credential is only ever a
 * secret reference and whose endpoint is only ever an approved environment.
 */
@RestController
@Tag(name = "Commercial billing setup", description = "The bank details invoices carry and the card merchant account")
public class CommercialBillingSetupController {

    private final PlatformBillingSettingsService settings;
    private final PlatformCardInstallationService installations;
    private final CurrentActor currentActor;

    public CommercialBillingSetupController(
            PlatformBillingSettingsService settings,
            PlatformCardInstallationService installations,
            CurrentActor currentActor) {
        this.settings = settings;
        this.installations = installations;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------ bank details

    @GetMapping("/api/v1/control-plane/billing/bank-details")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The bank details every invoice carries now",
            description = "configured is false while the row still holds the placeholder, and no invoice is "
                    + "issued until it is replaced. Invoices already issued keep the details of their own moment.")
    public ResponseEntity<BankDetailsView> bankDetails() {
        return ResponseEntity.ok(BankDetailsView.of(settings.current()));
    }

    @PostMapping("/api/v1/platform-admin/commercial/billing/bank-details")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Propose the bank details every invoice will carry",
            description = "Needs a second signature: the first call answers AWAITING_APPROVAL, and the identical "
                    + "call again after a different person approves it writes the details. The approver is shown "
                    + "the whole proposal.")
    public ResponseEntity<WalletChangeResponse> proposeBankDetails(@Valid @RequestBody ProposeBankDetailsRequest body) {
        WalletChangeOutcome outcome = settings.proposeBankDetails(
                new PlatformBillingSettingsService.BankDetails(
                        body.beneficiary(), body.bankName(), body.account(), body.mfo(), body.taxId()),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.ok(new WalletChangeResponse(outcome.status(), outcome.approvalRequestId()));
    }

    // ------------------------------------------------------ card installation

    @GetMapping("/api/v1/control-plane/billing/card-installations")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "HorecaOS's own card merchant accounts",
            description = "At most one is ACTIVE. The secret reference is never returned: secretConfigured says "
                    + "whether one is named.")
    public ResponseEntity<List<CardInstallationView>> cardInstallations() {
        return ResponseEntity.ok(
                installations.list().stream().map(CardInstallationView::of).toList());
    }

    @PostMapping("/api/v1/platform-admin/commercial/billing/card-installations")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Declare a card merchant account, in DRAFT",
            description = "The provider type must have an adapter in this build, the environment must come from "
                    + "the approved catalogue, and the credential is a provider_payment secret reference — the "
                    + "value is written to the secrets manager and never passes through this API.")
    public ResponseEntity<CardInstallationCreated> createCardInstallation(
            @Valid @RequestBody CreateCardInstallationRequest body) {
        UUID id = installations.create(
                body.providerType(),
                body.environmentCode(),
                body.displayName(),
                body.secretReference(),
                body.externalAccountReference(),
                body.configuration() == null ? Map.of() : body.configuration(),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.ok(new CardInstallationCreated(id));
    }

    @PostMapping("/api/v1/platform-admin/commercial/billing/card-installations/{installationId}/activation")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Make this the account cards are charged through",
            description = "Refused while another is active, and for a test double outside a local or test run. "
                    + "Cards bound under a previous account must be added again.")
    public ResponseEntity<CardInstallationView> activate(
            @PathVariable UUID installationId, @Valid @RequestBody TransitionRequest body) {
        return ResponseEntity.ok(CardInstallationView.of(installations.activate(
                installationId, body.expectedVersion(), actor(), body.reason(), correlationId())));
    }

    @PostMapping("/api/v1/platform-admin/commercial/billing/card-installations/{installationId}/suspension")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Stop charging through this account",
            description = "Every card tenant is then collected like an invoice tenant until an account is active.")
    public ResponseEntity<CardInstallationView> suspend(
            @PathVariable UUID installationId, @Valid @RequestBody TransitionRequest body) {
        return ResponseEntity.ok(CardInstallationView.of(installations.suspend(
                installationId, body.expectedVersion(), actor(), body.reason(), correlationId())));
    }

    // ------------------------------------------------------------------ helpers

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    // --------------------------------------------------------------- wire records

    public record BankDetailsView(
            boolean configured,
            String beneficiary,
            String bankName,
            String account,
            String mfo,
            String taxId,
            long version,
            String updatedBy,
            String updatedAt,
            @Nullable String approvedBy) {

        static BankDetailsView of(PlatformBillingSettings found) {
            return new BankDetailsView(
                    found.configured(),
                    found.beneficiary(),
                    found.bankName(),
                    found.account(),
                    found.mfo(),
                    found.taxId(),
                    found.version(),
                    found.updatedBy(),
                    found.updatedAt().toString(),
                    found.approvedBy());
        }
    }

    public record WalletChangeResponse(
            String status, @Nullable UUID approvalRequestId) {}

    public record CardInstallationView(
            UUID installationId,
            String providerType,
            @Nullable String environmentCode,
            String displayName,
            String status,
            boolean secretConfigured,
            @Nullable String externalAccountReference,
            Map<String, Object> configuration,
            long version,
            String createdAt,
            String updatedAt) {

        static CardInstallationView of(PlatformCardInstallation installation) {
            return new CardInstallationView(
                    installation.id(),
                    installation.providerType(),
                    installation.environmentCode(),
                    installation.displayName(),
                    installation.status(),
                    installation.secretReference() != null,
                    installation.externalAccountReference(),
                    new LinkedHashMap<>(installation.nonSensitiveConfig()),
                    installation.version(),
                    installation.createdAt().toString(),
                    installation.updatedAt().toString());
        }
    }

    public record CardInstallationCreated(UUID installationId) {}

    public record ProposeBankDetailsRequest(
            @NotBlank @Size(max = 255) String beneficiary,
            @NotBlank @Size(max = 255) String bankName,
            @NotBlank @Size(max = 64) String account,
            @NotBlank @Size(max = 32) String mfo,
            @NotBlank @Size(max = 32) String taxId,
            @NotBlank @Size(max = 1000) String reason) {}

    public record CreateCardInstallationRequest(
            @NotBlank @Size(max = 64) String providerType,
            @Nullable @Size(max = 64) String environmentCode,
            @NotBlank @Size(max = 255) String displayName,
            @Nullable @Size(max = 512) String secretReference,
            @Nullable @Size(max = 255) String externalAccountReference,
            @Nullable Map<String, Object> configuration,
            @NotBlank @Size(max = 1000) String reason) {}

    /** Boxed, so a body that omits the version is a validation answer and not a malformed-body one. */
    public record TransitionRequest(
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {}
}
