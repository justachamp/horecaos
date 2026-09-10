package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.tenancy.application.TenantProfileService;

class ReferenceDataControllerTests {

    @Test
    void namesTheMarketedCountriesAndShippedLocalesAndNothingInvented() {
        TenantProfileService profiles = mock(TenantProfileService.class);
        when(profiles.holidays()).thenReturn(List.of());
        var data = new ReferenceDataController(
                        profiles, () -> new AuthenticatedActor("admin", Set.of("platform-admin"), Map.of()))
                .get();

        assertThat(data.countries())
                .as("ADR 0034 names Uzbekistan, Kazakhstan, and Georgia")
                .extracting(ReferenceDataController.Country::code)
                .containsExactlyInAnyOrder("UZ", "KZ", "GE");

        assertThat(data.countries())
                .filteredOn(country -> country.code().equals("UZ"))
                .singleElement()
                .satisfies(uz -> {
                    assertThat(uz.defaultCurrency()).isEqualTo("UZS");
                    assertThat(uz.defaultTimezone()).isEqualTo("Asia/Tashkent");
                });

        assertThat(data.locales())
                .as("the three locales I18nService actually ships, no more")
                .extracting(ReferenceDataController.Locale::code)
                .containsExactlyInAnyOrder("ru", "uz-Latn", "en");
    }
}
