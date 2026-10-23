package uz.horecaos.platform.tenancy.web;

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
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;

/**
 * The languages <em>one brand</em> serves, readable before an account exists (ADR 0149, Decision 5
 * and the exit criterion: "a brand's supported set makes that brand's storefront ... speak Kazakh").
 *
 * <p>{@code GET /api/v1/storefront/locales} says which languages exist and where each is live: the
 * same answer for every tenant. It cannot say which of them <em>this</em> restaurant has chosen to
 * serve, so a storefront could only offer everything the platform has live, and a restaurant that
 * serves two languages would show a picker with three. This is the other half: the brand's own
 * set, default first, from {@code tenant.brand_locales}.
 *
 * <p>Unauthenticated, like the brand's terms and analytics reads beside it: a storefront paints its
 * language picker before a customer has signed in, and nothing here names a person. A brand id that
 * is not this tenant's answers exactly as a brand that has configured nothing -- the platform's
 * content languages -- so the read cannot be used to learn which brand ids exist. The set is a
 * <em>menu</em>: the storefront intersects it with the registry's content tier and with the
 * catalogues in its own build, and offers what remains.
 */
@RestController
@RequestMapping("/api/v1/storefront")
@Tag(name = "Storefront languages", description = "The languages one brand serves, readable before sign-in")
public class StorefrontBrandLocaleController {

    private final BrandLocaleLookup locales;

    public StorefrontBrandLocaleController(BrandLocaleLookup locales) {
        this.locales = locales;
    }

    @GetMapping("/tenants/{tenantId}/brands/{brandId}/locales")
    @Operation(
            summary = "The languages this brand serves, default first",
            description = "The brand's supported set (tenant.brand_locales), its own default first, then the "
                    + "platform's order. A brand that has chosen none answers with every language live in the "
                    + "registry's content tier, and says so (configured=false). A language the registry no "
                    + "longer has live is left out. Cacheable: it changes only when an operator edits the "
                    + "brand's languages.")
    public ResponseEntity<BrandLocalesResponse> brandLocales(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        TenantLocaleSet set = locales.brandLocaleSet(tenantId, brandId);
        List<String> offered = set.locales().stream()
                .filter(tag -> PlatformLocales.isActive(tag, Tier.CONTENT))
                .toList();
        // A default the registry no longer has live cannot be what a storefront starts in.
        String defaultLocale = offered.contains(set.defaultLocale())
                ? set.defaultLocale()
                : offered.isEmpty() ? PlatformLocales.fallback().tag() : offered.getFirst();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(new BrandLocalesResponse(offered, defaultLocale, set.configured()));
    }

    /**
     * @param locales the brand's content languages as registry tags, the default first
     * @param defaultLocale the language a storefront starts in for a visitor who has chosen none
     * @param configured whether the brand chose its own set, as opposed to sitting on the platform's content languages
     */
    public record BrandLocalesResponse(List<String> locales, String defaultLocale, boolean configured) {}
}
