package uz.horecaos.platform.payments.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.payments.application.PaymentMethodRegistryService;
import uz.horecaos.platform.payments.application.PaymentMethodRegistryService.CreateMethodCommand;
import uz.horecaos.platform.payments.application.PaymentMethodRegistryService.PaymentMethodDetail;
import uz.horecaos.platform.payments.application.PaymentMethodRegistryService.UpdateMethodCommand;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The tenant-scoped payment-method registry (ADR 0038, row 10.6) — the door
 * row 10.4b's channel matrix needs before its columns can be anything but a
 * frontend constant. See {@link PaymentMethodRegistryService}'s own doc for
 * why this exists and what it deliberately leaves immutable.
 *
 * <p>{@code TENANT} scope throughout, the narrowest the path supports: a
 * payment method is a tenant-level object exactly like {@code
 * tenant.sales_channels}, for the identical reason {@code
 * SalesChannelController}'s own doc gives.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/payment-methods")
@Tag(name = "Payment methods", description = "The tenant-owned registry every channel's payment matrix reads")
public class PaymentMethodController {

    private final PaymentMethodRegistryService methods;

    public PaymentMethodController(PaymentMethodRegistryService methods) {
        this.methods = methods;
    }

    @PostMapping
    @RequiresCapability(value = Capability.PAYMENT_METHOD_MANAGE, mutating = true)
    @Operation(
            summary = "Register a payment method",
            description = "The code and base type (fiscal responsibility) are fixed here and can "
                    + "never change afterward -- a tender already snapshots the responsibility at "
                    + "settlement time.")
    public ResponseEntity<PaymentMethodView> create(
            @PathVariable UUID tenantId, @Valid @RequestBody CreatePaymentMethodRequest body) {
        PaymentMethodDetail method = methods.create(
                tenantId,
                new CreateMethodCommand(
                        body.code(),
                        body.displayName(),
                        body.responsibility(),
                        body.icon(),
                        body.sortOrder(),
                        body.providerInstallationId(),
                        body.contractReference()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{methodId}")
                .buildAndExpand(method.id())
                .toUri();
        return ResponseEntity.created(location).body(PaymentMethodView.of(method));
    }

    @GetMapping
    @RequiresCapability(Capability.PAYMENT_METHOD_READ)
    @Operation(
            summary = "List the tenant's registered payment methods, ordered for display",
            description = "Disabled methods are listed too -- a historical order still names one, "
                    + "and an operator re-enabling a method should find it rather than re-create it. "
                    + "This is where row 10.4b's channel matrix gets its columns from.")
    public List<PaymentMethodView> list(@PathVariable UUID tenantId) {
        return methods.list(tenantId).stream().map(PaymentMethodView::of).toList();
    }

    @PutMapping("/{methodId}")
    @RequiresCapability(value = Capability.PAYMENT_METHOD_MANAGE, mutating = true)
    @Operation(
            summary = "Rename, re-icon, re-order or re-bind a method",
            description = "The code and base type never change here.")
    public PaymentMethodView update(
            @PathVariable UUID tenantId,
            @PathVariable UUID methodId,
            @Valid @RequestBody UpdatePaymentMethodRequest body,
            @RequestParam int expectedVersion) {
        return PaymentMethodView.of(methods.update(
                tenantId,
                methodId,
                new UpdateMethodCommand(
                        body.displayName(),
                        body.icon(),
                        body.sortOrder(),
                        body.providerInstallationId(),
                        body.contractReference()),
                expectedVersion));
    }

    @PutMapping("/{methodId}/translations")
    @RequiresCapability(value = Capability.PAYMENT_METHOD_MANAGE, mutating = true)
    @Operation(
            summary = "Replace a method's localized names",
            description = "Whole-set replace, never per-locale: an editor submitting three tabs at "
                    + "once must not interleave with another editor's save.")
    public PaymentMethodView replaceTranslations(
            @PathVariable UUID tenantId, @PathVariable UUID methodId, @Valid @RequestBody LocalizedNamesRequest body) {
        return PaymentMethodView.of(methods.replaceTranslations(tenantId, methodId, body.byLocale()));
    }

    @PostMapping("/{methodId}/activate")
    @RequiresCapability(value = Capability.PAYMENT_METHOD_MANAGE, mutating = true)
    @Operation(summary = "Activate a disabled payment method")
    public PaymentMethodView activate(
            @PathVariable UUID tenantId, @PathVariable UUID methodId, @RequestParam int expectedVersion) {
        return PaymentMethodView.of(methods.activate(tenantId, methodId, expectedVersion));
    }

    @PostMapping("/{methodId}/disable")
    @RequiresCapability(value = Capability.PAYMENT_METHOD_MANAGE, mutating = true)
    @Operation(
            summary = "Disable a payment method",
            description = "A channel still naming this method in its matrix is unaffected here -- "
                    + "row 10.4b's own confirmation is what stops an operator disabling the last "
                    + "method an active channel can sell through.")
    public PaymentMethodView disable(
            @PathVariable UUID tenantId, @PathVariable UUID methodId, @RequestParam int expectedVersion) {
        return PaymentMethodView.of(methods.disable(tenantId, methodId, expectedVersion));
    }

    record CreatePaymentMethodRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String responsibility,
            @Size(max = 64) @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Size(max = 120) @Nullable String contractReference) {}

    record UpdatePaymentMethodRequest(
            @NotBlank @Size(max = 120) String displayName,
            @Size(max = 64) @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Size(max = 120) @Nullable String contractReference) {}

    record LocalizedNamesRequest(@NotNull Map<String, String> byLocale) {}

    /** What a settings screen shows. */
    public record PaymentMethodView(
            UUID id,
            String code,
            String displayName,
            Map<String, String> localizedNames,
            String responsibility,
            boolean settlesFromBalance,
            String status,
            @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Nullable String contractReference,
            int version) {

        static PaymentMethodView of(PaymentMethodDetail detail) {
            return new PaymentMethodView(
                    detail.id(),
                    detail.code(),
                    detail.displayName(),
                    detail.localizedNames(),
                    detail.responsibility(),
                    detail.settlesFromBalance(),
                    detail.status(),
                    detail.icon(),
                    detail.sortOrder(),
                    detail.providerInstallationId(),
                    detail.contractReference(),
                    detail.version());
        }
    }
}
