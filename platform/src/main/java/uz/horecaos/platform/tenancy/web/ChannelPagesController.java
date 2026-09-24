package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.tenancy.application.ChannelPageService;
import uz.horecaos.platform.tenancy.application.TenantResourceNotFoundException;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageSlug;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageVersion;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageVersionSummary;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Row 10.5's static pages, authored from the operations console.
 *
 * <p>A thin adapter over {@link ChannelPageService}, mirroring {@code
 * legal.web.OperationsTermsController}'s own split between history/current/
 * one-version reads and a publish write — every rule (versions are
 * append-only, at least one language is required) is the service's.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/sales-channels/{channelId}/setup/pages")
@Tag(name = "Channel static pages", description = "Row 10.5: about, contacts, delivery terms, privacy/offer")
public class ChannelPagesController {

    private final ChannelPageService pages;
    private final CurrentActor currentActor;

    public ChannelPagesController(ChannelPageService pages, CurrentActor currentActor) {
        this.pages = pages;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(summary = "This channel's page publishing history, every slug, newest first")
    public List<PageVersionSummaryView> history(@PathVariable UUID tenantId, @PathVariable UUID channelId) {
        return pages.history(tenantId, channelId).stream()
                .map(PageVersionSummaryView::of)
                .toList();
    }

    @GetMapping("/{slug}/current")
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(
            summary = "The version of this page currently in force",
            description = "published: false with empty contents when this channel has never "
                    + "published this page -- the storefront's /pages/{slug} answers not-found for "
                    + "the same channel.")
    public PageVersionView current(
            @PathVariable UUID tenantId, @PathVariable UUID channelId, @PathVariable String slug) {
        ChannelPageSlug parsed = ChannelPageSlug.require(slug);
        return pages.current(tenantId, channelId, parsed)
                .map(PageVersionView::of)
                .orElseGet(() -> PageVersionView.unpublished(parsed));
    }

    @GetMapping("/{slug}/{version}")
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(summary = "One historical version of this page, exactly as it was published")
    public PageVersionView get(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @PathVariable String slug,
            @PathVariable int version) {
        ChannelPageSlug parsed = ChannelPageSlug.require(slug);
        return pages.version(tenantId, channelId, parsed, version)
                .map(PageVersionView::of)
                .orElseThrow(() -> new TenantResourceNotFoundException("No such page version"));
    }

    @PostMapping("/{slug}")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Publish the next version of this page",
            description = "Creates a new version; never edits a prior one. At least one language "
                    + "is required. A language omitted from this version simply has no translation "
                    + "-- the storefront answers 'not available in this language' for it, never an "
                    + "older or newer version's text.")
    public ResponseEntity<PageVersionView> publish(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @PathVariable String slug,
            @Valid @RequestBody PublishRequest request) {

        ChannelPageSlug parsed = ChannelPageSlug.require(slug);
        ChannelPageVersion published = pages.publish(
                tenantId,
                channelId,
                parsed,
                request.contentsByLocale(),
                currentActor.get().subject());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath(
                        "/api/v1/control-plane/tenants/{tenantId}/sales-channels/{channelId}/setup/pages/{slug}/{version}")
                .buildAndExpand(tenantId, channelId, slug, published.version())
                .toUri();
        return ResponseEntity.created(location).body(PageVersionView.of(published));
    }

    record PublishRequest(@NotEmpty Map<String, String> contentsByLocale) {}

    record PageVersionSummaryView(
            UUID id, String slug, int version, Set<String> locales, String publishedBy, Instant publishedAt) {
        static PageVersionSummaryView of(ChannelPageVersionSummary summary) {
            return new PageVersionSummaryView(
                    summary.id(),
                    summary.slug().slug(),
                    summary.version(),
                    summary.locales(),
                    summary.publishedBy(),
                    summary.publishedAt());
        }
    }

    record PageVersionView(
            boolean published,
            String slug,
            @Nullable UUID id,
            @Nullable Integer version,
            Map<String, String> contentsByLocale,
            @Nullable String publishedBy,
            @Nullable Instant publishedAt) {

        static PageVersionView of(ChannelPageVersion version) {
            return new PageVersionView(
                    true,
                    version.slug().slug(),
                    version.id(),
                    version.version(),
                    version.contentsByLocale(),
                    version.publishedBy(),
                    version.publishedAt());
        }

        static PageVersionView unpublished(ChannelPageSlug slug) {
            return new PageVersionView(false, slug.slug(), null, null, Map.of(), null, null);
        }
    }
}
