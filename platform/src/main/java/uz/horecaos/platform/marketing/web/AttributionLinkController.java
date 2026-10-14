package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.application.AttributionLinkService;
import uz.horecaos.platform.marketing.application.AttributionLinkUrls;
import uz.horecaos.platform.marketing.application.AttributionLinkUrls.Rendered;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore.AttributionLinkRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0044 "Attribution and referrals" (operations §6.6a): trackable
 * acquisition links a marketer mints for a campaign or an influencer.
 *
 * <p>No response here carries a phone number, an email, or a customer's
 * name — the same posture {@link OperationsMarketingController}'s own doc
 * states, for the same reason (ADR 0029): a link is a marketing artefact,
 * never a record of who clicked it.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/attribution-links")
@Tag(name = "Attribution links", description = "Trackable acquisition links (ADR 0044, operations 6.6a)")
public class AttributionLinkController {

    private final AttributionLinkService links;
    private final AttributionLinkUrls urls;
    private final CurrentActor currentActor;

    public AttributionLinkController(
            AttributionLinkService links, AttributionLinkUrls urls, CurrentActor currentActor) {
        this.links = links;
        this.urls = urls;
        this.currentActor = currentActor;
    }

    @PostMapping
    @RequiresCapability(value = Capability.MARKETING_LINK_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Mint a trackable acquisition link",
            description = "A website ?ref={token} link or a Telegram startapp deep link, for a "
                    + "campaign or an influencer. The token is opaque and short — a Telegram start "
                    + "deep link payload is limited to 64 URL-safe characters (ADR 0044).")
    public ResponseEntity<AttributionLinkResponse> mint(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody MintRequest body) {

        UUID id = links.mint(
                tenantId,
                brandId,
                body.label(),
                body.ownerNote(),
                body.channel(),
                body.destinationType(),
                body.destinationId(),
                body.validUntil(),
                actorId());
        AttributionLinkRow minted = links.require(tenantId, id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AttributionLinkResponse.of(
                        minted, urls.render(tenantId, brandId, minted.channel(), minted.token())));
    }

    @GetMapping
    @RequiresCapability(value = Capability.MARKETING_LINK_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every acquisition link this brand has minted, newest first",
            description = "Each carries url, the link as it is shared, built on the server from the brand's "
                    + "verified storefront hostname, the bot's username and the Mini App's short name; or "
                    + "unavailable, naming what is missing, and never both (ADR 0183).")
    public ResponseEntity<List<AttributionLinkResponse>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        AttributionLinkUrls.Renderer renderer = urls.forBrand(tenantId, brandId);
        return ResponseEntity.ok(links.list(tenantId, brandId).stream()
                .map(link -> AttributionLinkResponse.of(link, renderer.render(link.channel(), link.token())))
                .toList());
    }

    @PostMapping("/{linkId}/archives")
    @RequiresCapability(value = Capability.MARKETING_LINK_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Retire a link — it stops being minted new clicks against, and its history stays")
    public ResponseEntity<Void> archive(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID linkId) {

        AttributionLinkRow link = links.require(tenantId, linkId);
        if (!link.brandId().equals(brandId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No attribution link " + linkId + " belongs to this brand");
        }
        links.archive(tenantId, linkId, actorId());
        return ResponseEntity.accepted().build();
    }

    private UUID actorId() {
        String subject = currentActor.get().subject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This principal has no identifier that can be recorded as an author");
        }
    }

    public record MintRequest(
            @NotBlank @Size(max = 120) String label,
            @Nullable @Size(max = 500) String ownerNote,
            @NotBlank String channel,
            @NotBlank String destinationType,
            @Nullable UUID destinationId,
            @Nullable Instant validUntil) {}

    public record AttributionLinkResponse(
            UUID linkId,
            String label,
            String token,
            @Nullable String ownerNote,
            String channel,
            String destinationType,
            @Nullable UUID destinationId,
            String status,
            Instant validFrom,
            @Nullable Instant validUntil,
            int clickCount,
            UUID createdBy,
            Instant createdAt,
            @Nullable String url,
            @Nullable String unavailable) {

        static AttributionLinkResponse of(AttributionLinkRow row, Rendered rendered) {
            return new AttributionLinkResponse(
                    row.id(),
                    row.label(),
                    row.token(),
                    row.ownerNote(),
                    row.channel(),
                    row.destinationType(),
                    row.destinationId(),
                    row.status(),
                    row.validFrom(),
                    row.validUntil(),
                    row.clickCount(),
                    row.createdBy(),
                    row.createdAt(),
                    rendered.url(),
                    rendered.unavailable() == null
                            ? null
                            : rendered.unavailable().name());
        }
    }
}
