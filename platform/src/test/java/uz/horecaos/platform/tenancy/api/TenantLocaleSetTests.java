package uz.horecaos.platform.tenancy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet.BrandChoice;

/**
 * Row 10.12's decision for a tenant-scoped vocabulary: the union of the tenant's
 * brands' supported locales, the default taken from the tenant's first brand.
 *
 * <p>Pure arithmetic over {@link BrandChoice}, so the ordering and the
 * unconfigured-brand rule are pinned without a database; {@code
 * CommentPresetControllerTests} and {@code OperationsRegionControllerEndpointTests}
 * prove the same answer arrives over HTTP from real {@code tenant.brand_locales}
 * rows.
 */
class TenantLocaleSetTests {

    @Test
    @DisplayName("a tenant with no brand sits on the platform triple, ru first, unconfigured")
    void noBrandsIsThePlatformFallback() {
        TenantLocaleSet set = TenantLocaleSet.union(List.of());

        assertThat(set.locales()).containsExactly("ru", "uz-Latn", "en");
        assertThat(set.defaultLocale()).isEqualTo("ru");
        assertThat(set.configured()).isFalse();
    }

    @Test
    @DisplayName("the union of two brands' sets, with the FIRST brand's default first")
    void unionWithTheFirstBrandsDefault() {
        // Brand A offers uz-Latn (default) and en; brand B offers ru (its own
        // default) and en. The union is all three; the default is A's, because A
        // is first -- and B's ru default must NOT win, which is what a
        // "most common default" or "last brand wins" rule would have answered.
        TenantLocaleSet set = TenantLocaleSet.union(List.of(
                new BrandChoice(List.of("en", "uz-Latn"), "uz-Latn"), new BrandChoice(List.of("en", "ru"), "ru")));

        assertThat(set.defaultLocale()).isEqualTo("uz-Latn");
        assertThat(set.locales()).containsExactly("uz-Latn", "ru", "en");
        assertThat(set.configured()).isTrue();
    }

    @Test
    @DisplayName("a brand that has configured nothing contributes the platform triple, not nothing")
    void anUnconfiguredBrandWidensTheUnion() {
        // Brand A narrowed itself to en only; brand B never configured a set and so
        // authors in the platform triple everywhere (LocaleSet's own fallback). A
        // union that treated B as empty would hide ru and uz-Latn from the editor
        // B's operators can otherwise use.
        TenantLocaleSet set =
                TenantLocaleSet.union(List.of(new BrandChoice(List.of("en"), "en"), new BrandChoice(List.of(), null)));

        assertThat(set.locales()).containsExactly("en", "ru", "uz-Latn");
        assertThat(set.defaultLocale()).isEqualTo("en");
        assertThat(set.configured()).isTrue();
    }

    @Test
    @DisplayName("when the first brand is unconfigured the default is the platform's ru")
    void anUnconfiguredFirstBrandDefaultsToRu() {
        TenantLocaleSet set = TenantLocaleSet.union(
                List.of(new BrandChoice(List.of(), null), new BrandChoice(List.of("en", "uz-Latn"), "uz-Latn")));

        assertThat(set.defaultLocale())
                .as("the first brand decides, and an unconfigured brand's default is ru")
                .isEqualTo("ru");
        assertThat(set.locales()).containsExactly("ru", "uz-Latn", "en");
        assertThat(set.configured()).isTrue();
    }

    @Test
    @DisplayName("a locale outside the platform triple is kept, after the triple, in code order")
    void anotherLocaleIsKeptAfterTheTriple() {
        TenantLocaleSet set = TenantLocaleSet.union(List.of(new BrandChoice(List.of("tg", "ru", "kaa"), "ru")));

        assertThat(set.locales()).containsExactly("ru", "kaa", "tg");
    }

    @Test
    @DisplayName("a configured first brand with no marked default falls back to its first locale, deterministically")
    void aHandEditedRowWithNoDefaultStillNamesOne() {
        TenantLocaleSet set = TenantLocaleSet.union(List.of(new BrandChoice(List.of("en", "ru"), null)));

        assertThat(set.defaultLocale()).isEqualTo("en");
        assertThat(set.locales()).first().isEqualTo("en");
    }
}
