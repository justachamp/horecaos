package uz.horecaos.platform.content.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.content.api.ContentPolicies.EditorialPolicy;
import uz.horecaos.platform.content.api.ContentPolicies.PublishingPolicy;
import uz.horecaos.platform.content.application.ContentAuthoringService;
import uz.horecaos.platform.content.application.ContentAuthoringService.CreateInput;
import uz.horecaos.platform.content.application.ContentAuthoringService.DraftInput;
import uz.horecaos.platform.content.application.ContentHealthService;
import uz.horecaos.platform.content.application.ContentHealthService.Unhealthy;
import uz.horecaos.platform.content.application.ContentHomeService;
import uz.horecaos.platform.content.application.ContentHostService;
import uz.horecaos.platform.content.application.ContentHostService.AllowedHost;
import uz.horecaos.platform.content.application.ContentPolicyService;
import uz.horecaos.platform.content.application.ContentPolicyService.Policies;
import uz.horecaos.platform.content.application.ContentPublicationService;
import uz.horecaos.platform.content.application.ContentStatsService;
import uz.horecaos.platform.content.application.ContentViews.ItemPage;
import uz.horecaos.platform.content.application.ContentViews.ItemView;
import uz.horecaos.platform.content.application.ContentViews.RevisionSummary;
import uz.horecaos.platform.content.application.ContentViews.RevisionView;
import uz.horecaos.platform.content.application.HomeDocument;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentPlacement;
import uz.horecaos.platform.content.domain.ItemState;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's storefront content, as its staff author it (ADR 0157, Staff API).
 *
 * <p>Brand scope throughout: content belongs to a brand, never to a location. Four capabilities --
 * {@code content.read}, {@code content.author}, {@code content.publish} and {@code content.manage}
 * (the link allowlist and the brand's two content policies). Every change takes the item's version
 * in {@code If-Match} and answers {@code STALE_VERSION} on a mismatch; every {@code POST} takes an
 * {@code Idempotency-Key}. A draft is in front of nobody; only a publication reaches customers.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/content")
@Tag(
        name = "Storefront content",
        description = "Banners, promo cards, product groups, stories, pop-ups and editorial entries")
public class ContentOperationsController {

    private final ContentAuthoringService authoring;
    private final ContentPublicationService publication;
    private final ContentHomeService home;
    private final ContentHealthService health;
    private final ContentHostService hosts;
    private final ContentPolicyService policies;
    private final ContentStatsService stats;
    private final CurrentActor currentActor;

    public ContentOperationsController(
            ContentAuthoringService authoring,
            ContentPublicationService publication,
            ContentHomeService home,
            ContentHealthService health,
            ContentHostService hosts,
            ContentPolicyService policies,
            ContentStatsService stats,
            CurrentActor currentActor) {
        this.authoring = authoring;
        this.publication = publication;
        this.home = home;
        this.health = health;
        this.hosts = hosts;
        this.policies = policies;
        this.stats = stats;
        this.currentActor = currentActor;
    }

    // ================================================================== items

    @GetMapping("/items")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's content items, in the order a placement is read in",
            description = "`visibility` (LIVE, SCHEDULED, EXPIRED) is computed from the live revision's window "
                    + "at the moment of the read and is never stored. Each row carries `problems[]`, the "
                    + "publication gates run as warnings. When the list is of one `placement` it carries an "
                    + "`orderToken` to present back when ordering that placement.")
    public ItemPage listItems(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(required = false) @Nullable String placement,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String visibility,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return authoring.list(
                tenantId,
                brandId,
                kind == null ? null : parseKind(kind),
                placement == null ? null : parsePlacement(placement),
                state == null ? null : parseState(state),
                visibility,
                cursor,
                limit);
    }

    @PostMapping("/items")
    @RequiresCapability(value = Capability.CONTENT_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Create an item and its first draft",
            description = "A draft is in front of nobody. An editorial kind needs the content.editorial.enabled "
                    + "entitlement (403 ENTITLEMENT_REQUIRED) and the brand's content.editorial policy (422 "
                    + "CONTENT_KIND_NOT_ENABLED).")
    public ResponseEntity<ItemView> createItem(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateItemRequest body) {
        ItemView created = authoring.create(
                tenantId,
                brandId,
                actor(),
                new CreateInput(
                        parseKind(body.kind()),
                        body.placement() == null ? null : parsePlacement(body.placement()),
                        body.internalName(),
                        body.slug(),
                        body.draft()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.version()))
                .body(created);
    }

    @GetMapping("/items/{itemId}")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One item: its live revision, its draft and the ETag to change it with")
    public ResponseEntity<ItemView> getItem(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID itemId) {
        return versioned(authoring.get(tenantId, brandId, itemId));
    }

    @PutMapping("/items/{itemId}/draft")
    @RequiresCapability(value = Capability.CONTENT_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace the draft",
            description =
                    "Validates the grammar of the document (unknown keys are refused) and returns the "
                            + "publication gates as `problems[]`, warnings that become refusals at publication. Requires If-Match.")
    public ResponseEntity<ItemView> replaceItemDraft(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            @Valid @RequestBody ReplaceDraftRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return versioned(authoring.replaceDraft(
                tenantId, brandId, actor(), itemId, expected, body.internalName(), body.draft()));
    }

    @DeleteMapping("/items/{itemId}/draft")
    @RequiresCapability(value = Capability.CONTENT_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Discard the draft", description = "The item and its revisions remain. Requires If-Match.")
    public ResponseEntity<ItemView> discardItemDraft(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            HttpServletRequest request) {
        return versioned(authoring.discardDraft(tenantId, brandId, itemId, AggregateVersion.requireIfMatch(request)));
    }

    @PostMapping("/items/{itemId}/publications")
    @RequiresCapability(value = Capability.CONTENT_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish the draft as the next revision",
            description = "One transaction: the gates, the insert-only revision with its content hash, the live "
                    + "pointer, the audit fact and the outbox row. 422 UNPROCESSABLE_STATE carries errors[].code "
                    + "from a closed list; two concurrent publishes have exactly one winner and a 409. "
                    + "Requires If-Match.")
    public ResponseEntity<ItemView> publishItem(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            @Valid @RequestBody(required = false) @Nullable ContentReasonRequest body,
            HttpServletRequest request) {
        return versioned(publication.publish(
                tenantId, brandId, actor(), itemId, AggregateVersion.requireIfMatch(request), reasonOf(body)));
    }

    @PostMapping("/items/{itemId}/withdrawals")
    @RequiresCapability(value = Capability.CONTENT_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Take the item out of front of customers",
            description = "The live pointer is cleared and every revision is kept. Reaches customers within one "
                    + "cache window (a minute). Requires If-Match.")
    public ResponseEntity<ItemView> withdrawItem(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            @Valid @RequestBody(required = false) @Nullable ContentReasonRequest body,
            HttpServletRequest request) {
        return versioned(publication.withdraw(
                tenantId, brandId, actor(), itemId, AggregateVersion.requireIfMatch(request), reasonOf(body)));
    }

    @PostMapping("/items/{itemId}/archives")
    @RequiresCapability(value = Capability.CONTENT_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Retire the item from lists",
            description = "Withdraws it first if it is live. Requires If-Match.")
    public ResponseEntity<ItemView> archiveItem(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            @Valid @RequestBody(required = false) @Nullable ContentReasonRequest body,
            HttpServletRequest request) {
        return versioned(publication.archive(
                tenantId, brandId, actor(), itemId, AggregateVersion.requireIfMatch(request), reasonOf(body)));
    }

    @GetMapping("/items/{itemId}/stats")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "An item's anonymous view counts",
            description = "Impressions, clicks, dismissals and story completions per revision, tenant day and "
                    + "channel. Client-reported and labelled so: anyone can inflate them, no device, customer "
                    + "or network identifier is stored, and they are not a metric the reporting layer consumes. "
                    + "Defaults to the last 30 days.")
    public StatsResponse itemStats(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to) {
        authoring.get(tenantId, brandId, itemId);
        LocalDate last = to == null ? LocalDate.now(java.time.ZoneOffset.UTC) : to;
        LocalDate first = from == null ? last.minusDays(29) : from;
        var rows = stats.stats(tenantId, brandId, itemId, first, last);
        return new StatsResponse(
                true,
                first,
                last,
                rows.stream()
                        .map(r -> new StatsRow(
                                r.revisionNo(),
                                r.day(),
                                r.channelCode(),
                                r.impressions(),
                                r.clicks(),
                                r.dismissals(),
                                r.completions()))
                        .toList());
    }

    // ============================================================== revisions

    @GetMapping("/items/{itemId}/revisions")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(summary = "The immutable history of an item, newest first")
    public List<RevisionSummary> listRevisions(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID itemId) {
        return authoring.revisions(tenantId, brandId, itemId);
    }

    @GetMapping("/items/{itemId}/revisions/{revisionNo}")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One revision with its document: what a customer may have been shown")
    public RevisionView getRevision(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            @PathVariable int revisionNo) {
        return authoring.revision(tenantId, brandId, itemId, revisionNo);
    }

    @PostMapping("/items/{itemId}/revisions/{revisionNo}/republications")
    @RequiresCapability(value = Capability.CONTENT_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Clone an older revision into the draft",
            description = "Publishing it is a separate act. A revision whose media was released is refused with "
                    + "CONTENT_REVISION_MEDIA_RELEASED. Requires If-Match.")
    public ResponseEntity<ItemView> republishRevision(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID itemId,
            @PathVariable int revisionNo,
            HttpServletRequest request) {
        return versioned(authoring.cloneRevisionIntoDraft(
                tenantId, brandId, actor(), itemId, revisionNo, AggregateVersion.requireIfMatch(request)));
    }

    // =============================================================== placements

    @PutMapping("/placements/{placement}/order")
    @RequiresCapability(value = Capability.CONTENT_PUBLISH, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Order a placement",
            description = "Takes effect at once and is not a revision. The body names every non-archived item of "
                    + "the placement once and presents the order token the list returned; STALE_VERSION if the "
                    + "order moved since.")
    public PlacementOrderResponse orderPlacement(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String placement,
            @Valid @RequestBody OrderRequest body) {
        ContentAuthoringService.Ordered ordered = authoring.reorder(
                tenantId, brandId, actor(), parsePlacement(placement), body.ids(), body.orderToken(), body.reason());
        return new PlacementOrderResponse(ordered.ids(), ordered.orderToken());
    }

    // ================================================================ preview

    @GetMapping("/preview")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The storefront document built from drafts",
            description = "The same assembler as the public read, with every draft overlaid, so the preview "
                    + "cannot drift from production. `asOf` previews a schedule.")
    public HomeDocument previewHome(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale,
            @RequestParam(required = false) @Nullable Instant asOf) {
        return home.preview(tenantId, brandId, channel, locale, asOf);
    }

    @GetMapping("/health")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "What is wrong with what customers are being shown",
            description = "Live items with an unresolved target, a promotion that is not live, a link host that is "
                    + "no longer allowed, a missing language or an image that has not rendered. The storefront has "
                    + "already degraded each quietly; this is the operator's view of the same problems.")
    public List<Unhealthy> contentHealth(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return health.health(tenantId, brandId);
    }

    // ============================================================= allowlist

    @GetMapping("/allowed-hosts")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The hosts an external link may point at",
            description = "The hosts an author added, and the tenant's verified channel hostnames, which are read "
                    + "and never copied.")
    public List<AllowedHost> listAllowedHosts(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return hosts.list(tenantId, brandId);
    }

    @PostMapping("/allowed-hosts")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Allow a host, once",
            description = "One at a time: a bare lower-case DNS name, no wildcard, no port, no scheme. An audited "
                    + "security fact.")
    public ResponseEntity<HostResponse> addAllowedHost(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody HostRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new HostResponse(hosts.add(tenantId, brandId, actor(), body.host(), body.reason())));
    }

    @DeleteMapping("/allowed-hosts/{host}")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Stop allowing a host",
            description = "A live link to it is served as NONE from the next read. An audited security fact.")
    public ResponseEntity<Void> removeAllowedHost(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable String host) {
        hosts.remove(tenantId, brandId, actor(), host);
        return ResponseEntity.noContent().build();
    }

    // ============================================================== policies

    @GetMapping("/policies")
    @RequiresCapability(value = Capability.CONTENT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's two content policies and whether the plan lets the editorial tier exist",
            description = "content.publishing (tightening only) and content.editorial (which editorial kinds the "
                    + "brand runs, all off by default). Each carries the version a save is checked against.")
    public Policies getPolicies(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return policies.get(tenantId, brandId);
    }

    @PutMapping("/policies/publishing")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish the next version of content.publishing",
            description = "Requires If-Match carrying the policy version last read.")
    public Policies setPublishingPolicy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody PublishingPolicyRequest body,
            HttpServletRequest request) {
        return policies.setPublishing(
                tenantId,
                brandId,
                actor(),
                Math.toIntExact(AggregateVersion.requireIfMatch(request)),
                new PublishingPolicy(body.requireAllBrandLocales()),
                reason(body.reason()));
    }

    @PutMapping("/policies/editorial")
    @RequiresCapability(value = Capability.CONTENT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish the next version of content.editorial",
            description = "Requires If-Match carrying the policy version last read.")
    public Policies setEditorialPolicy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody EditorialPolicyRequest body,
            HttpServletRequest request) {
        return policies.setEditorial(
                tenantId,
                brandId,
                actor(),
                Math.toIntExact(AggregateVersion.requireIfMatch(request)),
                new EditorialPolicy(body.news(), body.gallery(), body.recipes(), body.vacancies()),
                reason(body.reason()));
    }

    // ================================================================ helpers

    private String actor() {
        return currentActor.get().subject();
    }

    private static @Nullable String reasonOf(@Nullable ContentReasonRequest body) {
        return body == null ? null : body.reason();
    }

    private static String reason(@Nullable String reason) {
        return reason == null || reason.isBlank() ? "content policy" : reason.strip();
    }

    private static ResponseEntity<ItemView> versioned(ItemView view) {
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(view.version())).body(view);
    }

    private static ContentKind parseKind(String kind) {
        return ContentKind.find(kind).orElseThrow(() -> badParameter("kind", "Not a content kind"));
    }

    private static ContentPlacement parsePlacement(String placement) {
        return ContentPlacement.find(placement).orElseThrow(() -> badParameter("placement", "Not a placement"));
    }

    private static ItemState parseState(String state) {
        try {
            return ItemState.valueOf(state.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw badParameter("state", "Not an item state");
        }
    }

    private static ApiException badParameter(String name, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                message,
                Map.of("errors", List.of(new ApiProblem.FieldError(name, "UNKNOWN_VALUE", message))));
    }

    // ============================================================ wire shapes

    /**
     * @param draft the first draft
     * @param slug  editorial kinds only
     */
    public record CreateItemRequest(
            @NotBlank String kind,
            @Nullable String placement,
            @NotBlank @Size(max = 120) String internalName,
            @Nullable @Size(max = 80) String slug,
            @NotNull Map<String, Object> document,
            @Nullable List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {

        DraftInput draft() {
            return new DraftInput(document, channelCodes, startsAt, endsAt, promotionId, locationId);
        }
    }

    public record ReplaceDraftRequest(
            @Nullable @Size(max = 120) String internalName,
            @NotNull Map<String, Object> document,
            @Nullable List<String> channelCodes,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable UUID promotionId,
            @Nullable UUID locationId) {

        DraftInput draft() {
            return new DraftInput(document, channelCodes, startsAt, endsAt, promotionId, locationId);
        }
    }

    public record OrderRequest(
            @NotNull List<UUID> ids,
            @NotBlank String orderToken,
            @Nullable @Size(max = 1000) String reason) {}

    public record PlacementOrderResponse(List<UUID> ids, String orderToken) {}

    /** {@code clientReported} is always true: say so on the screen. */
    public record StatsResponse(boolean clientReported, LocalDate from, LocalDate to, List<StatsRow> rows) {}

    public record StatsRow(
            int revisionNo,
            LocalDate day,
            String channelCode,
            long impressions,
            long clicks,
            long dismissals,
            long completions) {}

    /**
     * Why, in the author's words; optional, because publishing a banner is not worth a mandatory
     * field. ADR 0027 requires a reason on every fact a user produces, so a stated phrase stands in.
     */
    public record ContentReasonRequest(
            @Nullable @Size(max = 1000) String reason) {}

    public record HostRequest(
            @NotBlank @Size(max = 253) String host,
            @Nullable @Size(max = 1000) String reason) {}

    public record HostResponse(String host) {}

    public record PublishingPolicyRequest(
            @Nullable Boolean requireAllBrandLocales,
            @Nullable String reason) {}

    public record EditorialPolicyRequest(
            @Nullable Boolean news,
            @Nullable Boolean gallery,
            @Nullable Boolean recipes,
            @Nullable Boolean vacancies,
            @Nullable String reason) {}
}
