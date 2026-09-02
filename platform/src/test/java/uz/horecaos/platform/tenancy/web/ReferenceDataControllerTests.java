package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReferenceDataControllerTests {

    @Test
    void namesTheMarketedCountriesAndShippedLocalesAndNothingInvented() {
        var data = new ReferenceDataController().get();

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
