package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.ChannelPreviewService;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.Cursor;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Publishing a menu (ADR 0016).
 *
 * <p>Validation is exposed separately from publishing so an operator can see
 * every problem before committing to anything, rather than discovering them one
 * failed publish at a time.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog")
@Tag(name = "Catalog publication", description = "Validation, publication, and rollback")
public class CatalogPublicationController {

    private final CatalogPublicationService publication;
    private final ChannelPreviewService channelPreview;
    private final JdbcCatalogStore store;
    private final CurrentActor currentActor;

    public CatalogPublicationController(
            CatalogPublicationService publication,
            ChannelPreviewService channelPreview,
            JdbcCatalogStore store,
            CurrentActor currentActor) {
        this.publication = publication;
        this.channelPreview = channelPreview;
        this.store = store;
        this.currentActor = currentActor;
    }

    // ADR 0016 listed this as a POST, but validation has no effect, and ADR 0031's
    // gate is right that a POST must. GET is both honest and cacheable.
    @GetMapping("/catalogs/{catalogId}/validation")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Report every problem without publishing",
            description = "Returns stable codes and the entity each one is about, because "
                    + "\"a product has no variant\" is unactionable without knowing which product.")
    public ResponseEntity<ValidationResponse> validate(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID catalogId) {
        try {
            ValidationFinding.Report report = publication.validate(tenantId, brandId, catalogId);
            return ResponseEntity.ok(ValidationResponse.of(report));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @GetMapping("/catalogs/{catalogId}/draft-preview")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The content hash the draft would publish as right now (IA 4.6)",
            description = "Nothing is written. A channel card compares this against the hash of "
                    + "its own last PUBLISHED history entry to render \"Черновик отличается от "
                    + "опубликованного\" versus \"Актуально\" before an operator commits to "
                    + "publishing.")
    public ResponseEntity<DraftPreviewResponse> draftPreview(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID catalogId) {
        try {
            var preview = publication.previewDraft(tenantId, brandId, catalogId);
            return ResponseEntity.ok(new DraftPreviewResponse(
                    preview.contentHash(), preview.itemCount(), preview.channelContentHashes()));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @GetMapping("/catalogs/{catalogId}/channels/{channelId}/preview")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "What one channel would receive at one branch if the draft were published now (ADR 0138)",
            description = "A dry run of the publication pipeline addressed at a channel: the draft is "
                    + "snapshotted and validated as publish does it, then assembled by the same code a "
                    + "customer's menu read goes through, so the gates apply in ADR 0138's order -- the "
                    + "branch's offerings (or its bound named menu), the channel's exclusions, the "
                    + "channel's price plane (or no price at all when the aggregator sets it), and the "
                    + "channel's images over the item's own. Nothing is written, no hash is minted, and "
                    + "the result cannot be fetched again by reference. Name the branch with locationId "
                    + "or the marketplace binding with bindingId; a channel that sells at exactly one "
                    + "branch needs neither. Products are cursor-paginated; findings, categories and "
                    + "modifier groups ride the first page only.")
    public ChannelPreviewResponse channelPreview(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID catalogId,
            @PathVariable UUID channelId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @RequestParam(required = false) @Nullable UUID bindingId,
            @RequestParam(required = false) @Nullable String locale,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        // The cursor is pinned to the question it was minted for: a page of one
        // channel's menu is not the continuation of another's.
        String filterHash = sha256Prefix(String.join(
                "|",
                catalogId.toString(),
                channelId.toString(),
                String.valueOf(locationId),
                String.valueOf(bindingId),
                String.valueOf(locale)));
        UUID after = null;
        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = Cursor.decodeUnsigned(cursor, filterHash)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_REQUEST,
                            "This cursor was issued for a different preview; start the preview again"));
            try {
                after = UUID.fromString(decoded.sortKey());
            } catch (IllegalArgumentException malformed) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "This cursor does not name a product");
            }
        }
        int pageSize = Page.limitOrDefault(limit);

        try {
            ChannelPreviewService.ChannelPreview preview =
                    channelPreview.preview(new ChannelPreviewService.PreviewRequest(
                            tenantId, brandId, catalogId, channelId, locationId, bindingId, locale, after, pageSize));
            String next = preview.hasMore()
                    ? new Cursor(preview.products().getLast().productId().toString(), filterHash).encodeUnsigned()
                    : null;
            return ChannelPreviewResponse.of(preview, cursor == null || cursor.isBlank(), next, tenantId);
        } catch (ChannelPreviewService.UnknownPreviewTargetException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        } catch (ChannelPreviewService.PreviewTargetRequiredException ambiguous) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, ambiguous.getMessage());
        }
    }

    @GetMapping("/channels/{channelId}/preview-targets")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The branches a channel sells at, with the marketplace binding at each (ADR 0138)",
            description = "What a console offers to preview: one entry per branch the channel is active "
                    + "at, carrying the binding that covers it (and the ruleset it names) when the "
                    + "channel is backed by a marketplace installation.")
    public List<ChannelPreviewResponse.PreviewTargetView> previewTargets(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID channelId) {
        try {
            return channelPreview.targets(tenantId, brandId, channelId).stream()
                    .map(ChannelPreviewResponse.PreviewTargetView::of)
                    .toList();
        } catch (ChannelPreviewService.UnknownPreviewTargetException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    private static String sha256Prefix(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    @GetMapping("/publications")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's publication history (IA 4.6, Region 3)",
            description = "Newest first, every status including REJECTED — a rejected publication "
                    + "is recorded on purpose so 'why did publishing fail an hour ago' has an "
                    + "answer.")
    public ResponseEntity<List<PublicationHistoryResponse>> history(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @RequestParam(defaultValue = "50") int limit) {

        return ResponseEntity.ok(store.listPublications(tenantId, brandId, Math.min(limit, 200)).stream()
                .map(PublicationHistoryResponse::of)
                .toList());
    }

    @PostMapping("/catalogs/{catalogId}/publications")
    @RequiresCapability(value = Capability.CATALOG_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Snapshot, validate, and make live",
            description = "A rejected publication is still recorded with its report, so "
                    + "\"why did publishing fail an hour ago\" has an answer.")
    public ResponseEntity<PublicationResponse> publish(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID catalogId,
            @RequestParam(defaultValue = "STOREFRONT") String channel) {

        try {
            var result = publication.publish(tenantId, brandId, catalogId, channel, actorId());
            PublicationResponse body = new PublicationResponse(
                    result.publicationId(),
                    result.status(),
                    result.contentHash(),
                    ValidationResponse.of(result.report()));

            // A rejection is a completed request that produced a considered "no",
            // not a server fault: 200 with the report is more useful to an
            // operator UI than an error status with a message string.
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @PostMapping("/publications/{publicationId}/activate")
    @RequiresCapability(value = Capability.CATALOG_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Roll back to a previous publication",
            description = "Republishes an existing snapshot; it never edits history. The channel "
                    + "comes from the publication itself, so a rollback cannot retire one "
                    + "channel's menu and activate another channel's snapshot.")
    public ResponseEntity<PublicationResponse> rollback(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID publicationId) {

        try {
            var result = publication.rollbackTo(tenantId, brandId, publicationId);
            return ResponseEntity.ok(new PublicationResponse(
                    result.publicationId(),
                    result.status(),
                    result.contentHash(),
                    ValidationResponse.of(result.report())));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        } catch (IllegalStateException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage());
        }
    }

    private @Nullable UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    public record ValidationResponse(boolean publishable, List<FindingView> findings) {

        static ValidationResponse of(ValidationFinding.Report report) {
            return new ValidationResponse(
                    report.publishable(),
                    report.findings().stream().map(FindingView::of).toList());
        }
    }

    public record FindingView(
            String severity,
            String code,
            @Nullable String entityType,
            @Nullable UUID entityId,
            @Nullable String entityCode,
            String detail) {

        static FindingView of(ValidationFinding finding) {
            return new FindingView(
                    finding.severity().name(),
                    finding.code(),
                    finding.entityType() == null ? null : finding.entityType().name(),
                    finding.entityId(),
                    finding.entityCode(),
                    finding.detail());
        }
    }

    public record PublicationHistoryResponse(
            UUID publicationId,
            String channel,
            String status,
            String contentHash,
            @Nullable UUID createdBy,
            Instant createdAt,
            @Nullable Instant activatedAt,
            @Nullable Instant retiredAt,
            int itemCount) {

        static PublicationHistoryResponse of(JdbcCatalogStore.PublicationHistoryRow row) {
            return new PublicationHistoryResponse(
                    row.publicationId(),
                    row.channel(),
                    row.status().name(),
                    row.contentHash(),
                    row.createdBy(),
                    row.createdAt(),
                    row.activatedAt(),
                    row.retiredAt(),
                    row.itemCount());
        }
    }

    public record PublicationResponse(
            UUID publicationId, PublicationStatus status, String contentHash, ValidationResponse validation) {}

    /**
     * @param contentHash the draft, channel-agnostic
     * @param channelContentHashes the hash the draft would publish as on each channel that has a live
     *     menu, by channel code: compare a channel's live hash with its entry, falling back to
     *     {@code contentHash} for a channel without one
     */
    public record DraftPreviewResponse(String contentHash, int itemCount, Map<String, String> channelContentHashes) {}
}
