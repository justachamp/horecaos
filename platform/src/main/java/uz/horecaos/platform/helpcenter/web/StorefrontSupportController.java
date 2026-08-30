package uz.horecaos.platform.helpcenter.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.helpcenter.application.SupportQuery;
import uz.horecaos.platform.helpcenter.domain.SupportContent.FaqCategory;
import uz.horecaos.platform.helpcenter.domain.SupportContent.SocialLink;

/**
 * A brand's help content, for the storefront's support screens.
 *
 * <p>Unauthenticated, like the menu and the pickup locations. Somebody looking
 * up delivery hours or trying to find the Telegram channel is very often
 * somebody who does not have an account yet, and putting that behind a token
 * would answer the question only for people who no longer need to ask it.
 *
 * <p>Nothing here is personal data and nothing is per-customer, so the answer is
 * the same for every caller and is cached accordingly. Help content changes on
 * the order of weeks; five minutes is a compromise between an operator seeing
 * their edit and a storefront not re-fetching a static document on every
 * navigation.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/support")
@Tag(name = "Storefront support", description = "A brand's FAQ and its social links")
public class StorefrontSupportController {

    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final SupportQuery support;

    public StorefrontSupportController(SupportQuery support) {
        this.support = support;
    }

    @GetMapping("/faq")
    @Operation(
            summary = "The brand's published FAQ",
            description = "Categories in authored order, each with its published entries. Text "
                    + "resolves to the requested locale and falls back to any other published "
                    + "translation rather than to the authoring code. A brand with no published "
                    + "FAQ answers an empty list, which is a screen with no questions and not a "
                    + "failure.")
    public ResponseEntity<List<FaqResponse>> faq(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @RequestParam(defaultValue = "uz") String locale) {

        List<FaqResponse> body = support.faq(tenantId, brandId, locale).stream()
                .map(FaqResponse::of)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(body);
    }

    @GetMapping("/social-links")
    @Operation(
            summary = "Where a customer can reach this brand",
            description = "Published links in authored order. The platform is a checked "
                    + "vocabulary so a storefront can choose an icon from it, and the URL is "
                    + "constrained at the database to http(s), tel: and mailto:.")
    public ResponseEntity<List<SocialLinkResponse>> socialLinks(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {

        List<SocialLinkResponse> body = support.socialLinks(tenantId, brandId).stream()
                .map(SocialLinkResponse::of)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(body);
    }

    /** @param code the authoring handle, carried so a deep link can name a section. */
    public record FaqResponse(UUID categoryId, String code, String name, List<FaqEntryResponse> entries) {

        static FaqResponse of(FaqCategory category) {
            return new FaqResponse(
                    category.categoryId(),
                    category.code(),
                    category.name(),
                    category.entries().stream().map(FaqEntryResponse::of).toList());
        }
    }

    public record FaqEntryResponse(UUID entryId, String code, String question, String answer) {

        static FaqEntryResponse of(uz.horecaos.platform.helpcenter.domain.SupportContent.FaqEntry entry) {
            return new FaqEntryResponse(entry.entryId(), entry.code(), entry.question(), entry.answer());
        }
    }

    /** @param imageUrl null unless an operator overrode the platform's own artwork. */
    public record SocialLinkResponse(UUID linkId, String platform, String url, String imageUrl) {

        static SocialLinkResponse of(SocialLink link) {
            return new SocialLinkResponse(link.linkId(), link.platform(), link.url(), link.imageUrl());
        }
    }
}
