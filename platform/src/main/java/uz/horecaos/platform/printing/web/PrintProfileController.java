package uz.horecaos.platform.printing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.printing.application.ReceiptProfileService;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.ProfileRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's receipt profile (ADR 0154, Decision 4): whether the logo prints and up to three footer lines per
 * language, and nothing else. The seller, the order number, the totals, the QR and the fiscal block are not
 * fields, so there is nothing here that can move, resize or remove them.
 *
 * <p>Brand scope, because a footer and a logo are the brand's face on every branch's paper; {@code
 * print.manage} at brand scope is held by the tenant administrator and owner and not by a branch manager.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/print/receipt-profile")
@Tag(name = "Printing", description = "ADR 0154: printers, routes, the print queue, reprints and print agents")
public class PrintProfileController {

    private final ReceiptProfileService profiles;
    private final CurrentActor currentActor;

    public PrintProfileController(ReceiptProfileService profiles, CurrentActor currentActor) {
        this.profiles = profiles;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.BRAND)
    @Operation(
            operationId = "printGetReceiptProfile",
            summary = "The brand's receipt profile",
            description = "Whether the logo prints and the footer lines per language. A brand that never saved"
                    + " one has the default (logo off, no footer) at version 0.")
    public ResponseEntity<ReceiptProfileResponse> get(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        ProfileRow profile = profiles.get(tenantId, brandId);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(profile.version()))
                .body(ReceiptProfileResponse.of(profile));
    }

    @PutMapping
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            operationId = "printSaveReceiptProfile",
            summary = "Save the brand's receipt profile",
            description = "Under If-Match: 0 for a brand that has never saved one, otherwise the version the"
                    + " form was opened at. A footer is up to three lines per language (ru, uz-Latn, en),"
                    + " each at most 48 characters so that it fits an 80 mm roll. Refused 422 PRINTING_OFF"
                    + " while feature.printing is off for the tenant.")
    public ResponseEntity<ReceiptProfileResponse> put(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody ReceiptProfileRequest body,
            HttpServletRequest request) {
        ProfileRow saved = profiles.put(
                tenantId,
                brandId,
                Boolean.TRUE.equals(body.printLogo()),
                body.footerTexts() == null ? Map.of() : body.footerTexts(),
                AggregateVersion.requireIfMatch(request),
                currentActor.get().subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(saved.version()))
                .body(ReceiptProfileResponse.of(saved));
    }

    /**
     * @param printLogo   default false
     * @param footerTexts language to up to three lines
     */
    public record ReceiptProfileRequest(
            @NotNull Boolean printLogo, @Nullable Map<String, List<String>> footerTexts) {}

    public record ReceiptProfileResponse(
            boolean printLogo,
            Map<String, List<String>> footerTexts,
            int version,
            @Nullable String updatedBy,
            @Nullable Instant updatedAt) {

        static ReceiptProfileResponse of(ProfileRow row) {
            return new ReceiptProfileResponse(
                    row.printLogo(),
                    row.footerTexts(),
                    row.version(),
                    row.version() == 0 ? null : row.updatedBy(),
                    row.version() == 0 ? null : row.updatedAt());
        }
    }
}
