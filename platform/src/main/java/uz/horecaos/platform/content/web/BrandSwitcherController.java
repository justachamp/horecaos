package uz.horecaos.platform.content.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.content.application.BrandSwitcherService;
import uz.horecaos.platform.content.application.BrandSwitcherService.SwitcherCandidateView;
import uz.horecaos.platform.content.application.BrandSwitcherService.SwitcherEntryView;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The brand switcher's editor (gap-map row {@code 6.8}; ADR 0157 Release 4, ADR 0183 D17): which sibling brands this brand's
 * storefront offers a way to, in what order, and whether each is shown.
 *
 * <p>Reads need {@code content.read}; every change needs {@code content.manage} at the brand and is audited. An entry names a
 * website <em>channel</em>, and a visitor is sent to that channel's verified hostname as it is on the day they look, so the
 * list below says for each entry whether it {@code shown} now and, when it is not, the console can say why (hidden, no
 * verified hostname, the target brand inactive).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/content/brand-switcher")
@Tag(name = "Brand switcher", description = "A brand's way to its sibling brands' storefronts (ADR 0157 Release 4)")
public class BrandSwitcherController {

    private final BrandSwitcherService switcher;
    private final CurrentActor currentActor;

    public BrandSwitcherController(BrandSwitcherService switcher, CurrentActor currentActor) {
        this.switcher = switcher;
        this.currentActor = currentActor;
    }

    @GetMapping("/entries")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's switcher entries in order, each with whether a visitor would see it now",
            description =
                    "shown is false for a hidden entry, an entry whose target brand is inactive, and an entry whose "
                            + "website channel has no verified hostname.")
    public List<SwitcherEntryView> listSwitcherEntries(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return switcher.list(tenantId, brandId);
    }

    @GetMapping("/candidates")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The tenant's other brands and website channels an entry can name",
            description = "Other active brands that sell through an active website channel at one of their own active "
                    + "branches. hostname is null while the channel has no verified one.")
    public List<SwitcherCandidateView> listSwitcherCandidates(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return switcher.candidates(tenantId, brandId);
    }

    @PostMapping("/entries")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Offer a way to a sibling brand's storefront",
            description = "The entry names the target brand and one of the tenant's website channels; it links to that "
                    + "channel's verified hostname, checked on every read. A brand cannot offer itself, and the same brand "
                    + "and channel twice is a conflict.")
    public ResponseEntity<SwitcherEntryView> addSwitcherEntry(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SwitcherAddRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(switcher.add(
                        tenantId,
                        brandId,
                        body.targetBrandId(),
                        body.channelId(),
                        currentActor.get().subject()));
    }

    @PutMapping("/entries/{entryId}")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Show or hide an entry",
            description = "Requires If-Match carrying the entry's version last read.")
    public SwitcherEntryView setSwitcherEntryState(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID entryId,
            @Valid @RequestBody SwitcherStateRequest body,
            HttpServletRequest request) {
        return switcher.setState(
                tenantId,
                brandId,
                entryId,
                body.state(),
                Math.toIntExact(AggregateVersion.requireIfMatch(request)),
                currentActor.get().subject());
    }

    @DeleteMapping("/entries/{entryId}")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Remove an entry")
    public ResponseEntity<Void> removeSwitcherEntry(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID entryId) {
        switcher.remove(tenantId, brandId, entryId, currentActor.get().subject());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Set the order of the entries",
            description = "Names every entry of the brand's switcher exactly once, first to last.")
    public List<SwitcherEntryView> reorderSwitcherEntries(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SwitcherOrderRequest body) {
        return switcher.reorder(
                tenantId, brandId, body.ids(), currentActor.get().subject());
    }

    public record SwitcherAddRequest(
            @NotNull UUID targetBrandId, @NotNull UUID channelId) {}

    /** @param state {@code ACTIVE} or {@code HIDDEN} */
    public record SwitcherStateRequest(@NotNull String state) {}

    public record SwitcherOrderRequest(@NotEmpty List<@NotNull UUID> ids) {}
}
