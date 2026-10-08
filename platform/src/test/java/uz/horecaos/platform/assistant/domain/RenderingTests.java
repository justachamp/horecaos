package uz.horecaos.platform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.api.BranchDirectory.WeeklyWindow;

/** How platform values are put into words: money, hours, and the assistant's own sentences. */
class RenderingTests {

    @Test
    @DisplayName("money is whole som with the platform's own exponent, grouped in threes, with the language's word")
    void somIsWholeSom() {
        assertThat(MoneyFormat.format(45_000, "UZS", "ru")).contains("45 000 сум");
        assertThat(MoneyFormat.format(45_000, "UZS", "uz")).contains("45 000 so'm");
        assertThat(MoneyFormat.format(1_250_000, "UZS", "en")).contains("1 250 000 UZS");
        assertThat(MoneyFormat.format(500, "UZS", "en")).contains("500 UZS");
        assertThat(MoneyFormat.format(0, "UZS", "en")).contains("0 UZS");
    }

    @Test
    @DisplayName("a currency the platform has decided nothing about is not rendered, so nothing is quoted in it")
    void anUndecidedCurrencyIsNotRendered() {
        assertThat(MoneyFormat.format(4_500, "USD", "en")).isEmpty();
        assertThat(MoneyFormat.format(-1, "UZS", "en")).isEmpty();
        assertThat(MoneyFormat.supports("UZS")).isTrue();
        assertThat(MoneyFormat.supports("USD")).isFalse();
    }

    @Test
    @DisplayName("consecutive days with the same hours share a range, and a split shift lists both windows")
    void hoursAreCompact() {
        List<WeeklyWindow> weekdays = List.of(
                window(1, "09:00", "23:00"),
                window(2, "09:00", "23:00"),
                window(3, "09:00", "23:00"),
                window(4, "09:00", "23:00"),
                window(5, "09:00", "23:00"),
                window(6, "10:00", "00:00"),
                window(7, "10:00", "00:00"));

        assertThat(HoursText.format(weekdays, "en")).isEqualTo("Mon-Fri 09:00-23:00; Sat-Sun 10:00-00:00");
        assertThat(HoursText.format(weekdays, "ru")).isEqualTo("Пн-Пт 09:00-23:00; Сб-Вс 10:00-00:00");
        assertThat(HoursText.format(List.of(window(1, "09:00", "14:00"), window(1, "17:00", "23:00")), "en"))
                .isEqualTo("Mon 09:00-14:00, 17:00-23:00");
        assertThat(HoursText.format(List.of(window(3, "18:00", "02:00")), "uz")).isEqualTo("Ch 18:00-02:00");
    }

    @Test
    @DisplayName("the next opening is read on the branch's own clock")
    void nextOpeningUsesTheBranchClock() {
        ZonedDateTime tuesday = ZonedDateTime.of(2026, 10, 6, 10, 0, 0, 0, ZoneId.of("Asia/Tashkent"));

        assertThat(HoursText.dayAndTime(tuesday, "en")).isEqualTo("Tue 10:00");
        assertThat(HoursText.time(tuesday)).isEqualTo("10:00");
    }

    @Test
    @DisplayName("a handoff says why, then tells the truth about whether anybody is there")
    void aHandoffTellsTheTruthAboutPresence() {
        String online = CustomerWording.handoff("en", null, RefusalReason.NO_GROUNDING, true);
        String offline = CustomerWording.handoff("en", null, RefusalReason.NO_GROUNDING, false);

        assertThat(online).contains("won't guess").contains("will reply here").doesNotContain("Nobody");
        assertThat(offline).contains("Nobody from our team is online").contains("when the team is back");
        assertThat(offline).doesNotContain("shortly").doesNotContain("soon");
    }

    @Test
    @DisplayName("every refusal reason and topic has wording in all three languages")
    void everyReasonHasWordingInEveryLanguage() {
        for (String locale : ReplyLocale.SUPPORTED) {
            for (RefusalReason reason : RefusalReason.values()) {
                assertThat(CustomerWording.handoff(locale, null, reason, true))
                        .as(locale + " " + reason)
                        .isNotBlank();
            }
            for (EscalationTopic topic : EscalationTopic.values()) {
                assertThat(CustomerWording.handoff(locale, topic, null, false))
                        .as(locale + " " + topic)
                        .isNotBlank();
            }
            assertThat(CustomerWording.disclosure(locale)).as(locale).isNotBlank();
        }
    }

    @Test
    @DisplayName("the disclosure says an AI service processes the question and asks for no personal details")
    void theDisclosureIsHonest() {
        assertThat(CustomerWording.disclosure("en"))
                .contains("automated assistant")
                .contains("external AI service")
                .contains("don't send phone numbers");
        assertThat(CustomerWording.disclosure("ru"))
                .contains("автоматический помощник")
                .contains("ИИ-сервис");
        assertThat(CustomerWording.disclosure("uz"))
                .contains("avtomatik yordamchi")
                .contains("sun'iy intellekt");
    }

    private static WeeklyWindow window(int day, String opens, String closes) {
        return new WeeklyWindow(day, LocalTime.parse(opens), LocalTime.parse(closes));
    }
}
