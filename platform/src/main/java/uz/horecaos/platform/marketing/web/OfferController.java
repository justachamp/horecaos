package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.application.OfferService;
import uz.horecaos.platform.marketing.application.OfferService.OfferDraft;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore.OfferRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Offers: versioned references to something pricing or loyalty already owns (ADR 0112).
 *
 * <p>Writing needs {@link Capability#MARKETING_OFFER_MANAGE}; reading needs only
 * {@link Capability#CAMPAIGN_AUTHOR}, because the campaign editor selects from offers and
 * a marketer who cannot manage them must still be able to see them to choose. Nothing in
 * this API takes an amount, a percentage, a minimum basket or a number of points: an offer
 * names an existing promotion or accrual rule, and a request carrying both or neither is
 * refused. No response carries a contact value.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/offers")
@Tag(name = "Marketing offers", description = "Versioned references to a promotion or an accrual rule (ADR 0112)")
public class OfferController {

    private final OfferService offers;
    private final CurrentActor currentActor;

    public OfferController(OfferService offers, CurrentActor currentActor) {
        this.offers = offers;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every offer version this brand has, newest first",
            description = "What the campaign and scenario editors select from. Drafts, the published "
                    + "version of each lineage, and the versions it superseded.")
    public ResponseEntity<List<OfferResponse>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(
                offers.list(tenantId, brandId).stream().map(OfferResponse::of).toList());
    }

    @GetMapping("/{offerId}")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(summary = "One offer version")
    public ResponseEntity<OfferResponse> read(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID offerId) {
        OfferRow offer = offers.require(tenantId, brandId, offerId);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(offer.rowVersion()))
                .body(OfferResponse.of(offer));
    }

    @GetMapping("/{offerId}/versions")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(summary = "Every version of the lineage this offer belongs to, oldest first")
    public ResponseEntity<List<OfferResponse>> versions(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID offerId) {
        return ResponseEntity.ok(offers.lineage(tenantId, brandId, offerId).stream()
                .map(OfferResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.MARKETING_OFFER_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft an offer",
            description = "Exactly one of `pricingPromotionId` and `loyaltyAccrualRuleId`, and it must "
                    + "already exist for this brand. There is no field in which to state a discount or "
                    + "a points award: pricing decides what a benefit is worth and loyalty mints points. "
                    + "Written as a draft; publishing is a separate act.")
    public ResponseEntity<OfferResponse> create(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody OfferRequest body) {
        OfferRow created = offers.create(tenantId, brandId, body.toDraft(), actor(), actorId(), correlationId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.rowVersion()))
                .body(OfferResponse.of(created));
    }

    @PutMapping("/{offerId}")
    @RequiresCapability(value = Capability.MARKETING_OFFER_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Rewrite a draft offer",
            description = "Refused for a published version: its meaning is what every approved scenario "
                    + "that names it was approved against. Make a new version instead.")
    public ResponseEntity<OfferResponse> rewrite(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID offerId,
            @Valid @RequestBody OfferRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        OfferRow rewritten = offers.rewriteDraft(
                tenantId, brandId, offerId, (int) expected, body.toDraft(), actor(), correlationId());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(rewritten.rowVersion()))
                .body(OfferResponse.of(rewritten));
    }

    @PostMapping("/{offerId}/versions")
    @RequiresCapability(value = Capability.MARKETING_OFFER_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a new version of an offer",
            description = "A new draft in the same lineage. The published version stays in force until "
                    + "the new one is published, and a scenario that named the old one keeps naming it.")
    public ResponseEntity<OfferResponse> newVersion(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID offerId,
            @Valid @RequestBody OfferRequest body) {
        OfferRow created =
                offers.newVersion(tenantId, brandId, offerId, body.toDraft(), actor(), actorId(), correlationId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.rowVersion()))
                .body(OfferResponse.of(created));
    }

    @PostMapping("/{offerId}/publications")
    @RequiresCapability(value = Capability.MARKETING_OFFER_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish a draft offer",
            description = "Puts the draft in force and supersedes the lineage's previously published "
                    + "version. Re-checks that the promotion or rule it points at still exists and is not "
                    + "history, and that its template has wording, before anything is published.")
    public ResponseEntity<OfferResponse> publish(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID offerId,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        OfferRow published =
                offers.publish(tenantId, brandId, offerId, (int) expected, actor(), actorId(), correlationId());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(published.rowVersion()))
                .body(OfferResponse.of(published));
    }

    @PostMapping("/{offerId}/retirements")
    @RequiresCapability(value = Capability.MARKETING_OFFER_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Retire an offer version",
            description = "It stops being selectable, and every scenario that already names it stops "
                    + "offering it at the guest's next step, with the reason recorded.")
    public ResponseEntity<OfferResponse> retire(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID offerId,
            @Valid @RequestBody RetireRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        OfferRow retired =
                offers.retire(tenantId, brandId, offerId, (int) expected, actor(), body.reason(), correlationId());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(retired.rowVersion()))
                .body(OfferResponse.of(retired));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
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

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    /**
     * What an author sends. Deliberately nothing here states a benefit.
     *
     * <p>Both references are nullable and the service refuses a request with both or
     * neither, with a sentence that says why, which a Bean Validation constraint cannot.
     */
    public record OfferRequest(
            @NotBlank @Size(max = 120) String displayName,
            @Nullable UUID pricingPromotionId,
            @Nullable UUID loyaltyAccrualRuleId,
            @NotNull Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable UUID audienceId,
            @NotEmpty List<@NotBlank String> allowedChannels,
            @NotBlank @Size(max = 64) String templateKey,
            @Nullable Integer templateVersion,
            @Nullable @Size(max = 255) String bannerImageReference) {

        OfferDraft toDraft() {
            return new OfferDraft(
                    displayName,
                    pricingPromotionId,
                    loyaltyAccrualRuleId,
                    validFrom,
                    validUntil,
                    audienceId,
                    allowedChannels,
                    templateKey,
                    templateVersion,
                    bannerImageReference);
        }
    }

    public record RetireRequest(@NotBlank @Size(max = 500) String reason) {}

    public record OfferResponse(
            UUID offerId,
            UUID lineageId,
            int versionNumber,
            String status,
            String displayName,
            @Nullable UUID pricingPromotionId,
            @Nullable UUID loyaltyAccrualRuleId,
            Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable UUID audienceId,
            List<String> allowedChannels,
            String templateKey,
            @Nullable Integer templateVersion,
            @Nullable String bannerImageReference,
            UUID createdBy,
            @Nullable UUID publishedBy,
            @Nullable Instant publishedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        static OfferResponse of(OfferRow row) {
            return new OfferResponse(
                    row.id(),
                    row.lineageId(),
                    row.versionNumber(),
                    row.status(),
                    row.displayName(),
                    row.pricingPromotionId(),
                    row.loyaltyAccrualRuleId(),
                    row.validFrom(),
                    row.validUntil(),
                    row.audienceId(),
                    row.allowedChannels(),
                    row.templateKey(),
                    row.templateVersion(),
                    row.bannerImageReference(),
                    row.createdBy(),
                    row.publishedBy(),
                    row.publishedAt(),
                    row.rowVersion(),
                    row.createdAt(),
                    row.updatedAt());
        }
    }
}
