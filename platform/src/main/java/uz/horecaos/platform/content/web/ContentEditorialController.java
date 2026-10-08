package uz.horecaos.platform.content.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.content.application.ContentEditorialService;
import uz.horecaos.platform.content.application.ContentEditorialService.EntryDetail;
import uz.horecaos.platform.content.application.ContentEditorialService.EntryPage;
import uz.horecaos.platform.content.application.ContentEditorialService.Features;
import uz.horecaos.platform.content.application.ContentMetrics;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The editorial tier as a storefront reads it (ADR 0157, Decision 7; Release 5): news, gallery albums,
 * recipes and a vacancy listing.
 *
 * <p>Unauthenticated and cacheable like {@link ContentStorefrontController}, and for the same
 * reasons. A route exists for a brand only while <em>both</em> the {@code content.editorial.enabled}
 * entitlement and the brand's {@code content.editorial} policy say yes for that kind; when either does
 * not, the answer is the 404 an unknown route would give, and "off", "never existed", "unknown brand"
 * and "unknown channel" are one answer so nothing can be learned by probing. {@code /features} is the
 * single read that says which kinds a brand runs, so a storefront can decide whether to show a link.
 *
 * <p>There is deliberately no application endpoint under {@code /careers}: the platform stores no
 * applicant data, which is what keeps the recruitment exclusion standing.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/content")
@Tag(name = "Storefront editorial", description = "News, gallery, recipes and vacancies a brand has published")
public class ContentEditorialController {

    private final ContentEditorialService editorial;
    private final ContentMetrics metrics;

    public ContentEditorialController(ContentEditorialService editorial, ContentMetrics metrics) {
        this.editorial = editorial;
        this.metrics = metrics;
    }

    @GetMapping("/features")
    @Operation(
            summary = "Which editorial kinds a brand runs",
            description = "News, gallery, recipes and vacancies, each true only while the tenant's plan "
                    + "includes the editorial tier and the brand has turned the kind on. All false when it "
                    + "has not; an unknown brand is a 404.")
    public ResponseEntity<Features> editorialFeatures(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return StorefrontCaching.cacheable(
                editorial.features(tenantId, brandId).orElseThrow(() -> notFound("No such brand")));
    }

    // ------------------------------------------------------------------- news

    @GetMapping("/news")
    @Operation(summary = "A brand's published news, newest first")
    public ResponseEntity<EntryPage> listNews(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return list(ContentKind.NEWS, tenantId, brandId, channel, locale, cursor, limit);
    }

    @GetMapping("/news/{slug}")
    @Operation(summary = "One news article, its body as typed blocks")
    public ResponseEntity<EntryDetail> getNews(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String slug,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale) {
        return detail(ContentKind.NEWS, tenantId, brandId, slug, channel, locale);
    }

    // ---------------------------------------------------------------- gallery

    @GetMapping("/gallery")
    @Operation(summary = "A brand's published gallery albums, newest first")
    public ResponseEntity<EntryPage> listGallery(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return list(ContentKind.GALLERY_ALBUM, tenantId, brandId, channel, locale, cursor, limit);
    }

    @GetMapping("/gallery/{slug}")
    @Operation(summary = "One gallery album with its images")
    public ResponseEntity<EntryDetail> getGalleryAlbum(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String slug,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale) {
        return detail(ContentKind.GALLERY_ALBUM, tenantId, brandId, slug, channel, locale);
    }

    // ---------------------------------------------------------------- recipes

    @GetMapping("/recipes")
    @Operation(summary = "A brand's published recipes, newest first")
    public ResponseEntity<EntryPage> listRecipes(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return list(ContentKind.RECIPE, tenantId, brandId, channel, locale, cursor, limit);
    }

    @GetMapping("/recipes/{slug}")
    @Operation(summary = "One recipe, its body as typed blocks")
    public ResponseEntity<EntryDetail> getRecipe(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String slug,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale) {
        return detail(ContentKind.RECIPE, tenantId, brandId, slug, channel, locale);
    }

    // --------------------------------------------------------------- vacancies

    @GetMapping("/careers")
    @Operation(
            summary = "A brand's published vacancies, newest first",
            description = "A listing only. A vacancy carries one outbound contact the tenant chose to "
                    + "publish (a phone number, an e-mail address, a Telegram or web address) and nothing "
                    + "of a candidate: the platform has no application form and stores no applicant data.")
    public ResponseEntity<EntryPage> listVacancies(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return list(ContentKind.VACANCY, tenantId, brandId, channel, locale, cursor, limit);
    }

    @GetMapping("/careers/{slug}")
    @Operation(summary = "One vacancy, with its branch and its one contact")
    public ResponseEntity<EntryDetail> getVacancy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String slug,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale) {
        return detail(ContentKind.VACANCY, tenantId, brandId, slug, channel, locale);
    }

    // ---------------------------------------------------------------- plumbing

    private ResponseEntity<EntryPage> list(
            ContentKind kind,
            UUID tenantId,
            UUID brandId,
            String channel,
            @Nullable String locale,
            @Nullable String cursor,
            @Nullable Integer limit) {
        return answered(
                kind,
                () -> editorial
                        .list(tenantId, brandId, kind, channel, locale, cursor, limit)
                        .orElseThrow(() -> notFound("No such listing")));
    }

    private ResponseEntity<EntryDetail> detail(
            ContentKind kind, UUID tenantId, UUID brandId, String slug, String channel, @Nullable String locale) {
        return answered(
                kind,
                () -> editorial
                        .detail(tenantId, brandId, kind, slug, channel, locale)
                        .orElseThrow(() -> notFound("No such entry")));
    }

    private <T> ResponseEntity<T> answered(ContentKind kind, Supplier<T> read) {
        try {
            T body = read.get();
            metrics.read("editorial_" + kind.name().toLowerCase(java.util.Locale.ROOT));
            return StorefrontCaching.cacheable(body);
        } catch (ApiException notFound) {
            if (notFound.errorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                metrics.read("editorial_not_found");
            }
            throw notFound;
        }
    }

    private static ApiException notFound(String message) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
