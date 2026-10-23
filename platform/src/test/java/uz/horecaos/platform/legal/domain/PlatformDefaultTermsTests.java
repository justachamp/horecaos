package uz.horecaos.platform.legal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The default terms carry the funnel notice ADR 0167 proposed (ADR 0183, the checklist 0167 still owed): the platform counts
 * registrations, baskets, checkouts and orders in total and never by person, and records nothing about what a visitor looks at.
 *
 * <p>The wording is a proposal counsel has not finalised (ADR 0167 open input 3, «Blocks: nothing»), so this pins what the
 * notice must still say if its words change: the counts, the "never by person", and the promise about what is not recorded.
 */
class PlatformDefaultTermsTests {

    @Test
    @DisplayName(
            "every language of the default terms says the platform counts in total and never by person, and records no browsing")
    void everyLanguageCarriesTheNotice() {
        assertThat(PlatformDefaultTerms.forLocale("en", "Tandir House"))
                .contains("HorecaOS counts registrations, baskets, checkouts and orders, in total and never by person")
                .contains("does not record what you look at or which pages you visit");
        assertThat(PlatformDefaultTerms.forLocale("ru", "Tandir House"))
                .contains("HorecaOS считает регистрации, корзины, оформления и заказы")
                .contains("никогда по отдельным людям")
                .contains("не записывает, что вы просматриваете");
        assertThat(PlatformDefaultTerms.forLocale("uz-Latn", "Tandir House"))
                .contains("HorecaOS ro'yxatdan o'tishlar, savatlar, rasmiylashtirishlar va buyurtmalarni")
                .contains("hech qachon alohida shaxslar bo'yicha emas")
                .contains("yozib bormaydi");
    }

    @Test
    @DisplayName("the wording changed, so the version moved: an acceptance of version 1 keeps meaning version 1")
    void theVersionMovedWithTheWording() {
        assertThat(PlatformDefaultTerms.VERSION).isEqualTo(2);
    }

    @Test
    @DisplayName("the notice sits inside the clause about personal data, before marketing, in every language")
    void theNoticeBelongsToThePersonalDataClause() {
        for (String locale : List.of("en", "ru", "uz-Latn")) {
            String text = PlatformDefaultTerms.forLocale(locale, "X");
            int notice = text.indexOf("HorecaOS");
            int marketing = text.indexOf("9. ");
            assertThat(notice).as(locale).isPositive();
            assertThat(marketing).as(locale).isGreaterThan(notice);
        }
    }
}
