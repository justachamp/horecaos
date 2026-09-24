package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.application.ChannelPageService;
import uz.horecaos.platform.tenancy.application.ChannelSetupService;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageSlug;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageVersion;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPresentation;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The public half of row 10.5's channel setup: SEO presentation and static
 * pages, unauthenticated and scoped by channel, no PII (ADR 0031).
 *
 * <p>{@code channel} is a tenant-defined channel <em>code</em>, resolved
 * through {@link SalesChannelLookup#byCode}, exactly like {@code
 * StorefrontCatalogController#menu}'s own {@code channel} parameter and
 * {@code AppConfig.channel} on the storefront side — never the channel's
 * UUID, which nothing on the storefront holds before this call.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/channels/{channel}")
@Tag(name = "Storefront channel presentation", description = "Row 10.5: a channel's public SEO facts and static pages")
public class StorefrontChannelSetupController {

    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final SalesChannelLookup channels;
    private final ChannelSetupService setup;
    private final ChannelPageService pages;

    public StorefrontChannelSetupController(
            SalesChannelLookup channels, ChannelSetupService setup, ChannelPageService pages) {
        this.channels = channels;
        this.setup = setup;
        this.pages = pages;
    }

    @GetMapping("/presentation")
    @Operation(
            summary = "This channel's SEO title/description and OG image",
            description = "The OG image, if set, is an asset id -- render it through "
                    + "StorefrontMediaController's /media/{assetId} redirect, the same path the "
                    + "menu already uses for product photos.")
    public ResponseEntity<PresentationView> presentation(@PathVariable UUID tenantId, @PathVariable String channel) {
        UUID channelId = requireChannel(tenantId, channel).id();
        ChannelPresentation presentation = setup.presentation(tenantId, channelId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(PresentationView.of(presentation));
    }

    @GetMapping("/pages/{slug}")
    @Operation(
            summary = "One static page, in the requested language",
            description = "Always the highest published version -- a client cannot ask for an "
                    + "older one. Not-found when this channel has never published this page, or "
                    + "has published it but not in the requested locale.")
    public ResponseEntity<PageView> page(
            @PathVariable UUID tenantId,
            @PathVariable String channel,
            @PathVariable String slug,
            @RequestParam String locale) {

        UUID channelId = requireChannel(tenantId, channel).id();
        ChannelPageSlug parsedSlug = parseSlug(slug);
        ChannelPageVersion version = pages.current(tenantId, channelId, parsedSlug)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This channel has no " + slug + " page"));
        String body = version.contentsByLocale().get(locale);
        if (body == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The " + slug + " page has no " + locale + " text");
        }
        return ResponseEntity.ok()
                .eTag("\"%s-%d\"".formatted(slug, version.version()))
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(new PageView(slug, locale, version.version(), body));
    }

    private SalesChannel requireChannel(UUID tenantId, String channel) {
        return channels.byCode(tenantId, channel)
                .filter(SalesChannel::sellable)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such channel"));
    }

    private static ChannelPageSlug parseSlug(String slug) {
        return ChannelPageSlug.parse(slug)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "\"" + slug + "\" is not a page"));
    }

    record PresentationView(
            @Nullable String seoTitle,
            @Nullable String seoDescription,
            @Nullable UUID ogImageAssetId) {
        static PresentationView of(ChannelPresentation presentation) {
            return new PresentationView(
                    presentation.seoTitle(), presentation.seoDescription(), presentation.ogImageAssetId());
        }
    }

    record PageView(String slug, String locale, int version, String body) {}
}
